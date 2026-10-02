package com.citylaundry.service;

import com.citylaundry.common.ApiException;
import com.citylaundry.model.*;
import com.citylaundry.model.Enums.*;
import com.citylaundry.repo.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 交付：合并洗护记录、复拍照片、会员扣费、赔付与售后时限 */
@Service
public class DeliveryService {
    private final LaundryOrderRepo orderRepo;
    private final GarmentItemRepo itemRepo;
    private final ProcessRecordRepo processRepo;
    private final GarmentPhotoRepo photoRepo;
    private final TransferRecordRepo transferRepo;
    private final MemberCardRepo cardRepo;
    private final MemberTransactionRepo txRepo;
    private final AfterSalesRecordRepo afterSalesRepo;
    private final CompensationCaseRepo caseRepo;
    private final TicketService ticketService;
    private final PaymentService paymentService;

    @Value("${app.after-sales-days:7}")
    private int afterSalesDays;

    public DeliveryService(LaundryOrderRepo orderRepo, GarmentItemRepo itemRepo,
                           ProcessRecordRepo processRepo, GarmentPhotoRepo photoRepo,
                           TransferRecordRepo transferRepo, MemberCardRepo cardRepo,
                           MemberTransactionRepo txRepo, AfterSalesRecordRepo afterSalesRepo,
                           CompensationCaseRepo caseRepo, TicketService ticketService,
                           PaymentService paymentService) {
        this.orderRepo = orderRepo;
        this.itemRepo = itemRepo;
        this.processRepo = processRepo;
        this.photoRepo = photoRepo;
        this.transferRepo = transferRepo;
        this.cardRepo = cardRepo;
        this.txRepo = txRepo;
        this.afterSalesRepo = afterSalesRepo;
        this.caseRepo = caseRepo;
        this.ticketService = ticketService;
        this.paymentService = paymentService;
    }

    /** 单件衣物应收价：客户确认过的成交价优先，否则按工艺/材质估价 */
    public static BigDecimal priceOf(GarmentItem item) {
        if (item.getFinalPrice() != null) return item.getFinalPrice();
        return priceOf(item.getProcessType() == null ? null : item.getProcessType());
    }

    /** 单件衣物洗护定价 */
    public static BigDecimal priceOf(ProcessType type) {
        if (type == null) return BigDecimal.ZERO;
        return switch (type) {
            case DRY_CLEAN -> new BigDecimal("30");
            case WET_CLEAN -> new BigDecimal("20");
            case LEATHER_CARE -> new BigDecimal("80");
            case LUXURY_CARE -> new BigDecimal("200");
            case BASIC -> new BigDecimal("10");
            case REJECTED -> BigDecimal.ZERO;
        };
    }

    /** 订单账单预览（交付前可查；退回件不计费，客户确认成交价优先） */
    public Map<String, Object> billOf(Long orderId) {
        LaundryOrder order = orderRepo.findById(orderId).orElseThrow(() -> ApiException.notFound("订单不存在"));
        List<GarmentItem> items = itemRepo.findByOrderIdOrderByIdAsc(orderId);
        List<Map<String, Object>> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        boolean negotiated = false;
        for (GarmentItem item : items) {
            if (item.getStatus() == GarmentStatus.RETURNED
                    || item.getStatus() == GarmentStatus.RECOVERED) continue; // 退回件不计费
            BigDecimal price = priceOf(item);
            if (item.getFinalPrice() != null) negotiated = true;
            subtotal = subtotal.add(price);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("itemCode", item.getItemCode());
            line.put("type", item.getType());
            line.put("processType", item.getProcessType());
            line.put("price", price);
            lines.add(line);
        }
        BigDecimal discount = BigDecimal.ZERO;
        // 团体折扣仅适用于未产生异常议价的订单
        if (order.getType() == OrderType.GROUP_WORKWEAR && !negotiated) {
            discount = subtotal.multiply(new BigDecimal("0.10")).setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal deliveryFee = order.isSelfPickup() ? BigDecimal.ZERO : new BigDecimal("5");
        BigDecimal total = subtotal.subtract(discount).add(deliveryFee);

        Map<String, Object> bill = new LinkedHashMap<>();
        bill.put("orderNo", order.getOrderNo());
        bill.put("lines", lines);
        bill.put("subtotal", subtotal);
        bill.put("groupDiscount", discount);
        bill.put("deliveryFee", deliveryFee);
        bill.put("selfPickup", order.isSelfPickup());
        bill.put("total", total);
        return bill;
    }

    /**
     * 交付订单（配送上门或门店自取共用）：
     * 校验全部衣物就绪 → 会员卡扣费 → 生成逐件售后记录（合并洗护/复拍/赔付摘要）。
     */
    @Transactional
    public Map<String, Object> deliver(UserAccount actor, Long orderId, String deliveryPhotoUrl) {
        LaundryOrder order = orderRepo.findById(orderId).orElseThrow(() -> ApiException.notFound("订单不存在"));
        if (order.getStatus() != OrderStatus.READY_FOR_DELIVERY) {
            throw ApiException.conflict("订单未就绪，不能交付: " + order.getStatus());
        }
        List<GarmentItem> items = itemRepo.findByOrderIdOrderByIdAsc(orderId);
        for (GarmentItem item : items) {
            if (item.getStatus() != GarmentStatus.READY_FOR_DELIVERY
                    && item.getStatus() != GarmentStatus.REJECTED
                    && item.getStatus() != GarmentStatus.RETURNED
                    && item.getStatus() != GarmentStatus.RECOVERED) {
                throw ApiException.conflict("衣物 " + item.getItemCode() + " 未就绪: " + item.getStatus());
            }
        }

        // 交付结算：通过支付单把预授权冻结转为请款扣款（幂等），退回件不计入
        Map<String, Object> bill = billOf(orderId);
        BigDecimal total = (BigDecimal) bill.get("total");
        PaymentOrder payment = paymentService.ofOrder(orderId);
        MemberCard card;
        if (payment != null) {
            BigDecimal alreadyCaptured = payment.getCapturedAmount();
            BigDecimal todo = total.subtract(alreadyCaptured);
            if (todo.signum() > 0) {
                payment = paymentService.capture(orderId, todo, "capture-deliver-" + orderId);
            }
            // 请款后释放估价冗余/退回件对应的剩余预授权冻结
            paymentService.releasePreauth(orderId, "release-deliver-" + orderId, "交付请款后释放剩余预授权");
            card = cardRepo.findById(payment.getCardId()).orElseThrow();
        } else {
            // 无支付单（数据兼容）：直接卡扣并生成流水
            card = cardRepo.findById(order.getMemberCardId()).orElseThrow();
            if (card.getBalance().compareTo(total) < 0) {
                throw ApiException.conflict("会员卡余额不足: 需 ¥" + total + "，当前 ¥" + card.getBalance());
            }
            card.setBalance(card.getBalance().subtract(total));
            card = cardRepo.save(card);
            MemberTransaction tx0 = new MemberTransaction();
            tx0.setCardId(card.getId());
            tx0.setOrderId(order.getId());
            tx0.setAmount(total.negate());
            tx0.setType("PAYMENT");
            tx0.setNote("订单 " + order.getOrderNo() + " 洗护费用扣款（无预授权兜底）");
            txRepo.save(tx0);
        }

        order.setTotalAmount(total);
        order.setStatus(OrderStatus.DELIVERED);
        orderRepo.save(order);

        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> handovers = new ArrayList<>();
        for (GarmentItem item : items) {
            // 已退回客户的衣物不参与交付、不生成售后记录
            if (item.getStatus() == GarmentStatus.RETURNED
                    || item.getStatus() == GarmentStatus.RECOVERED) {
                continue;
            }
            item.setStatus(GarmentStatus.DELIVERED);
            itemRepo.save(item);

            // 交付留影（可选）
            if (deliveryPhotoUrl != null && !deliveryPhotoUrl.isBlank()) {
                GarmentPhoto photo = new GarmentPhoto();
                photo.setItemId(item.getId());
                photo.setStage(PhotoStage.DELIVERY);
                photo.setUrl(deliveryPhotoUrl);
                photo.setUploadedBy(actor.getId());
                photoRepo.save(photo);
            }

            // 合并洗护记录 + 复拍照片 + 赔付 → 售后摘要
            String summary = buildHandoverSummary(item);
            AfterSalesRecord as = new AfterSalesRecord();
            as.setItemId(item.getId());
            as.setOrderId(order.getId());
            as.setDeliveredAt(now);
            as.setDeadline(now.plusDays(afterSalesDays));
            as.setStatus(AfterSalesStatus.ACTIVE);
            as.setHandoverSummary(summary);
            afterSalesRepo.save(as);

            Map<String, Object> h = new LinkedHashMap<>();
            h.put("itemCode", item.getItemCode());
            h.put("afterSalesDeadline", as.getDeadline());
            h.put("handoverSummary", summary);
            handovers.add(h);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderNo", order.getOrderNo());
        result.put("status", order.getStatus());
        result.put("charged", total);
        result.put("cardBalance", card.getBalance());
        result.put("bill", bill);
        result.put("handovers", handovers);
        return result;
    }

    /** 交付合并摘要：工艺记录 + 复拍照片 + 多站点交接 + 赔付记录 */
    private String buildHandoverSummary(GarmentItem item) {
        StringBuilder sb = new StringBuilder();
        sb.append("衣物[").append(item.getItemCode()).append("] ");
        List<ProcessRecord> processes = processRepo.findByItemIdOrderByStartedAtAsc(item.getId());
        for (ProcessRecord p : processes) {
            sb.append("工艺:").append(p.getProcessType())
              .append("(站点").append(p.getSiteId()).append(") ");
        }
        List<GarmentPhoto> reshoots = photoRepo.findByItemIdAndStage(item.getId(), PhotoStage.FACTORY_RESHOOT);
        sb.append("复拍照片:").append(reshoots.size()).append("张 ");
        List<TransferRecord> transfers = transferRepo.findByItemIdOrderByCreatedAtAsc(item.getId());
        sb.append("交接:").append(transfers.size()).append("次 ");
        List<CompensationCase> cases = caseRepo.findByItemId(item.getId());
        for (CompensationCase c : cases) {
            sb.append("赔付:").append(c.getCaseNo()).append("/¥").append(c.getAmount())
              .append("/").append(c.getStatus()).append(" ");
        }
        return sb.toString().trim();
    }

    /** 售后申诉（顾客质疑新破损）：校验售后时限 → 生成 DAMAGE_DISPUTE 工单 */
    @Transactional
    public ExceptionTicket claim(UserAccount customer, Long itemId, String description) {
        GarmentItem item = itemRepo.findById(itemId).orElseThrow(() -> ApiException.notFound("衣物不存在"));
        LaundryOrder order = orderRepo.findById(item.getOrderId()).orElseThrow();
        if (!order.getCustomerId().equals(customer.getId())) {
            throw ApiException.forbidden("只能对自己的衣物申诉");
        }
        AfterSalesRecord as = afterSalesRepo.findByItemId(itemId)
                .orElseThrow(() -> ApiException.conflict("该衣物没有售后记录（可能未交付）"));
        if (as.getStatus() == AfterSalesStatus.EXPIRED || as.getDeadline().isBefore(LocalDateTime.now())) {
            throw ApiException.conflict("售后期限已过(" + as.getDeadline() + ")，无法申诉");
        }
        as.setStatus(AfterSalesStatus.CLAIMED);
        afterSalesRepo.save(as);
        return ticketService.raise(customer, itemId, ExceptionType.DAMAGE_DISPUTE, description, null, null);
    }

    /** 售后到期扫描（演示用）：过期记录置 EXPIRED，衣物关闭 */
    @Transactional
    public Map<String, Object> sweepExpiredAfterSales() {
        int expired = 0;
        LocalDateTime now = LocalDateTime.now();
        for (AfterSalesRecord as : afterSalesRepo.findAll()) {
            if (as.getStatus() == AfterSalesStatus.ACTIVE && as.getDeadline().isBefore(now)) {
                as.setStatus(AfterSalesStatus.EXPIRED);
                afterSalesRepo.save(as);
                GarmentItem item = itemRepo.findById(as.getItemId()).orElse(null);
                if (item != null && item.getStatus() == GarmentStatus.DELIVERED) {
                    item.setStatus(GarmentStatus.CLOSED);
                    itemRepo.save(item);
                }
                expired++;
            }
        }
        return Map.of("expired", expired);
    }
}
