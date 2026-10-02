package com.citylaundry.service;

import com.citylaundry.common.ApiException;
import com.citylaundry.dto.ApiDtos.CreateOrderRequest;
import com.citylaundry.dto.ApiDtos.ItemRequest;
import com.citylaundry.model.*;
import com.citylaundry.model.Enums.*;
import com.citylaundry.repo.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class OrderService {
    private final LaundryOrderRepo orderRepo;
    private final GarmentItemRepo itemRepo;
    private final StoreRepo storeRepo;
    private final MemberCardRepo cardRepo;

    @Value("${app.high-value-threshold:2000}")
    private BigDecimal highValueThreshold;

    @Value("${app.high-risk-value-threshold:5000}")
    private BigDecimal highRiskValueThreshold;

    public OrderService(LaundryOrderRepo orderRepo, GarmentItemRepo itemRepo,
                        StoreRepo storeRepo, MemberCardRepo cardRepo) {
        this.orderRepo = orderRepo;
        this.itemRepo = itemRepo;
        this.storeRepo = storeRepo;
        this.cardRepo = cardRepo;
    }

    /** 顾客下单：记录衣物全量信息，评估风险，应用门店接单策略 */
    @Transactional
    public LaundryOrder createOrder(UserAccount customer, CreateOrderRequest req) {
        if (req.storeId() == null) throw ApiException.badRequest("缺少门店 storeId");
        Store store = storeRepo.findById(req.storeId())
                .orElseThrow(() -> ApiException.notFound("门店不存在"));
        if (req.items() == null || req.items().isEmpty()) {
            throw ApiException.badRequest("订单至少包含一件衣物");
        }
        OrderType type = parseOrderType(req.orderType());
        if (type == OrderType.GROUP_WORKWEAR && req.items().size() < 5) {
            throw ApiException.badRequest("团体工服订单至少 5 件衣物");
        }
        MemberCard card = cardRepo.findByCustomerId(customer.getId())
                .orElseThrow(() -> ApiException.badRequest("顾客没有会员卡，无法下单"));
        if (req.expectedDeliveryAt() == null || req.expectedDeliveryAt().isBefore(LocalDateTime.now())) {
            throw ApiException.badRequest("预计交付时间必须是未来时间");
        }
        if (isBlank(req.pickupAddress()) || isBlank(req.deliveryAddress())) {
            throw ApiException.badRequest("取送地址不能为空");
        }

        // 风险评估 + 门店接单策略
        List<RiskLevel> risks = req.items().stream().map(this::assessRisk).toList();
        long highCount = risks.stream().filter(r -> r == RiskLevel.HIGH).count();
        if (highCount > 0) {
            if (!store.isAcceptHighRisk()) {
                throw ApiException.conflict("门店[" + store.getName() + "]当前不接收高风险衣物，订单被拒绝");
            }
            long todayHigh = countTodayHighRisk(store.getId());
            if (todayHigh + highCount > store.getMaxHighRiskPerDay()) {
                throw ApiException.conflict("门店[" + store.getName() + "]今日高风险衣物接单已达上限("
                        + store.getMaxHighRiskPerDay() + ")，订单被拒绝");
            }
        }

        LaundryOrder order = new LaundryOrder();
        order.setCustomerId(customer.getId());
        order.setStoreId(store.getId());
        order.setType(type);
        order.setSelfPickup(Boolean.TRUE.equals(req.selfPickup()));
        order.setPickupAddress(req.pickupAddress());
        order.setDeliveryAddress(req.deliveryAddress());
        order.setMemberCardId(card.getId());
        order.setExpectedDeliveryAt(req.expectedDeliveryAt());
        order.setStatus(OrderStatus.CREATED);
        order.setExpectedDeliveryAt(req.expectedDeliveryAt());
        order.setBaseExpectedDeliveryAt(req.expectedDeliveryAt());
        order.setOrderNo("TMP");
        order = orderRepo.save(order);
        order.setOrderNo(String.format("O%08d", order.getId()));
        order = orderRepo.save(order);

        int seq = 1;
        for (ItemRequest ir : req.items()) {
            GarmentItem item = new GarmentItem();
            item.setOrderId(order.getId());
            item.setItemCode(String.format("G%08d-%02d", order.getId(), seq++));
            item.setType(requireText(ir.type(), "衣物类型"));
            item.setBrand(ir.brand());
            item.setMaterial(requireText(ir.material(), "材质"));
            item.setColor(ir.color());
            item.setStains(ir.stains());
            item.setExistingDamage(ir.existingDamage());
            item.setAccessories(ir.accessories());
            item.setCareLabelPresent(ir.careLabelPresent() == null || ir.careLabelPresent());
            item.setDeclaredValue(ir.declaredValue() == null ? BigDecimal.ZERO : ir.declaredValue());
            item.setLuxury(Boolean.TRUE.equals(ir.luxury()));
            item.setRiskLevel(assessRisk(ir));
            item.setStatus(GarmentStatus.REGISTERED);
            itemRepo.save(item);
        }
        return order;
    }

    /** 风险等级评估：奢侈品/高声明价值/特殊材质 → 高风险 */
    public RiskLevel assessRisk(ItemRequest ir) {
        BigDecimal value = ir.declaredValue() == null ? BigDecimal.ZERO : ir.declaredValue();
        String material = ir.material() == null ? "" : ir.material();
        if (Boolean.TRUE.equals(ir.luxury())
                || value.compareTo(highRiskValueThreshold) >= 0
                || containsAny(material, "真丝", "皮革", "皮草", "羊绒")) {
            return RiskLevel.HIGH;
        }
        if (value.compareTo(highValueThreshold) >= 0
                || Boolean.FALSE.equals(ir.careLabelPresent())
                || !isBlank(ir.stains())
                || !isBlank(ir.existingDamage())) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    /** 高价值衣物判定：奢侈品或声明价值达到阈值 → 专人复核流程 */
    public boolean isHighValue(GarmentItem item) {
        return item.isLuxury() || item.getDeclaredValue().compareTo(highValueThreshold) >= 0;
    }

    private long countTodayHighRisk(Long storeId) {
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        long count = 0;
        for (LaundryOrder o : orderRepo.findByStoreIdOrderByCreatedAtDesc(storeId)) {
            if (o.getCreatedAt().isBefore(dayStart)) continue;
            for (GarmentItem it : itemRepo.findByOrderIdOrderByIdAsc(o.getId())) {
                if (it.getRiskLevel() == RiskLevel.HIGH) count++;
            }
        }
        return count;
    }

    /**
 * 顾客确认取衣照片无误（新流程下仅记录认可，不直接放行送厂；
 * 放行须经骑手取件建档 + 门店收件质检/异常客户决策）。
 */
    @Transactional
    public LaundryOrder confirmPickup(UserAccount customer, Long orderId) {
        LaundryOrder order = getOwnOrder(customer, orderId);
        if (order.getStatus() != OrderStatus.CREATED) {
            throw ApiException.conflict("订单已离开拍照确认环节: " + order.getStatus());
        }
        for (GarmentItem item : itemRepo.findByOrderIdOrderByIdAsc(order.getId())) {
            if (item.getStatus() != GarmentStatus.PHOTOGRAPHED) {
                throw ApiException.conflict("衣物 " + item.getItemCode() + " 尚未完成拍照");
            }
        }
        // 仅确认照片；等待骑手确认取件
        return order;
    }

    public LaundryOrder getOwnOrder(UserAccount customer, Long orderId) {
        LaundryOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> ApiException.notFound("订单不存在"));
        if (!order.getCustomerId().equals(customer.getId())) {
            throw ApiException.forbidden("只能查看自己的订单");
        }
        return order;
    }

    public LaundryOrder getOrder(Long orderId) {
        return orderRepo.findById(orderId).orElseThrow(() -> ApiException.notFound("订单不存在"));
    }

    public List<LaundryOrder> myOrders(UserAccount customer) {
        return orderRepo.findByCustomerIdOrderByCreatedAtDesc(customer.getId());
    }

    public List<GarmentItem> itemsOf(Long orderId) {
        return itemRepo.findByOrderIdOrderByIdAsc(orderId);
    }

    private OrderType parseOrderType(String s) {
        if (isBlank(s)) return OrderType.NORMAL;
        try {
            return OrderType.valueOf(s);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("未知订单类型: " + s);
        }
    }

    static boolean containsAny(String text, String... keys) {
        for (String k : keys) if (text.contains(k)) return true;
        return false;
    }

    static boolean isBlank(String s) { return s == null || s.isBlank(); }

    static String requireText(String s, String field) {
        if (isBlank(s)) throw ApiException.badRequest("缺少必填字段: " + field);
        return s;
    }
}
