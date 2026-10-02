package com.citylaundry.service;

import com.citylaundry.common.ApiException;
import com.citylaundry.model.*;
import com.citylaundry.model.Enums.*;
import com.citylaundry.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 取件与门店收件质检。
 * 骑手确认取件时建立取洗档案（取件时间/位置/骑手留痕）并做支付预授权；
 * 送达门店后逐件质检，发现材质不符/高风险污渍/配件缺失/不可去除瑕疵
 * 一律转异常流程，禁止直接加价或继续洗护。
 */
@Service
public class StoreQcService {
    private final PickupRecordRepo pickupRepo;
    private final StoreQcRecordRepo qcRepo;
    private final LaundryOrderRepo orderRepo;
    private final GarmentItemRepo itemRepo;
    private final GarmentPhotoRepo photoRepo;
    private final GarmentStateMachine sm;
    private final PaymentService paymentService;
    private final PricingService pricing;
    private final TimelineService timeline;

    public StoreQcService(PickupRecordRepo pickupRepo, StoreQcRecordRepo qcRepo,
                          LaundryOrderRepo orderRepo, GarmentItemRepo itemRepo,
                          GarmentPhotoRepo photoRepo, GarmentStateMachine sm,
                          PaymentService paymentService, PricingService pricing,
                          TimelineService timeline) {
        this.pickupRepo = pickupRepo;
        this.qcRepo = qcRepo;
        this.orderRepo = orderRepo;
        this.itemRepo = itemRepo;
        this.photoRepo = photoRepo;
        this.sm = sm;
        this.paymentService = paymentService;
        this.pricing = pricing;
        this.timeline = timeline;
    }

    /**
     * 骑手确认取件：校验照片齐全 → 建立取件记录 → 逐件进入 PICKED_UP →
     * 订单进入 PICKED_UP（取洗档案建立）→ 按下单估价做支付预授权。
     */
    @Transactional
    public PickupRecord confirmPickup(UserAccount rider, Long orderId, String location,
                                      String customerSign, String note) {
        LaundryOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> ApiException.notFound("订单不存在"));
        if (order.getStatus() != OrderStatus.CREATED) {
            // 已取件则幂等返回原记录
            if (order.getStatus() == OrderStatus.PICKED_UP || order.getStatus() == OrderStatus.STORE_QC
                    || order.getStatus() == OrderStatus.EXCEPTION_PENDING
                    || order.getStatus() == OrderStatus.CONFIRMED || order.getStatus() == OrderStatus.IN_FACTORY) {
                return pickupRepo.findFirstByOrderIdOrderByPickedAtAsc(orderId).orElseThrow();
            }
            throw ApiException.conflict("订单当前状态不能取件: " + order.getStatus());
        }
        List<GarmentItem> items = itemRepo.findByOrderIdOrderByIdAsc(orderId);
        for (GarmentItem it : items) {
            if (it.getStatus() != GarmentStatus.PHOTOGRAPHED) {
                throw ApiException.conflict("衣物 " + it.getItemCode() + " 取件照片未齐，不能取件");
            }
        }
        if (location == null || location.isBlank()) throw ApiException.badRequest("取件位置 location 必填");

        PickupRecord rec = new PickupRecord();
        rec.setOrderId(orderId);
        rec.setRiderId(rider.getId());
        rec.setLocation(location);
        rec.setCustomerSign(customerSign);
        rec.setNote(note);
        rec = pickupRepo.save(rec);

        for (GarmentItem it : items) {
            sm.transit(it, GarmentStatus.PICKED_UP, "骑手确认取件");
            itemRepo.save(it);
        }
        order.setBaseExpectedDeliveryAt(order.getExpectedDeliveryAt());
        sm.transitOrder(order, OrderStatus.PICKED_UP, "骑手确认取件");
        orderRepo.save(order);

        // 预授权：按下单估价冻结（预授权只冻结不扣款）
        BigDecimal estimate = pricing.estimateOrder(order, items);
        paymentService.ensureForOrder(order);
        paymentService.preauth(orderId, estimate, "preauth-pickup-" + orderId);

        timeline.add(orderId, null, "PICKUP",
                "骑手[" + rider.getName() + "]取件，位置=" + location + "，件数=" + items.size()
                        + "，预授权 ¥" + estimate, rider, rec.getId() == null ? null : ("PK" + rec.getId()));
        return rec;
    }

    /** 骑手送达门店：逐件进入门店质检 */
    @Transactional
    public List<GarmentItem> arriveStore(UserAccount rider, Long orderId) {
        LaundryOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> ApiException.notFound("订单不存在"));
        if (order.getStatus() != OrderStatus.PICKED_UP) {
            throw ApiException.conflict("订单未处于已取件状态，不能送达门店: " + order.getStatus());
        }
        List<GarmentItem> items = itemRepo.findByOrderIdOrderByIdAsc(orderId);
        for (GarmentItem it : items) {
            if (it.getStatus() != GarmentStatus.PICKED_UP) {
                throw ApiException.conflict("衣物 " + it.getItemCode() + " 状态不是已取件: " + it.getStatus());
            }
            sm.transit(it, GarmentStatus.STORE_QC, "送达门店质检");
            itemRepo.save(it);
        }
        PickupRecord rec = pickupRepo.findFirstByOrderIdOrderByPickedAtAsc(orderId).orElseThrow();
        rec.setDeliveredToStoreAt(java.time.LocalDateTime.now());
        pickupRepo.save(rec);
        sm.transitOrder(order, OrderStatus.STORE_QC, "送达门店");
        orderRepo.save(order);
        timeline.add(orderId, null, "QC", "骑手送达门店，开始逐件收件质检", rider, null);
        return items;
    }

    /**
     * 门店逐件质检结论。passed=false 时必须同时创建异常项（走 ExceptionService）。
     */
    @Transactional
    public StoreQcRecord inspect(UserAccount inspector, GarmentItem item, String observedMaterial,
                                 String observedAccessories, String conclusion, boolean passed,
                                 Responsibility responsibility) {
        if (observedMaterial == null || observedMaterial.isBlank()) {
            throw ApiException.badRequest("质检必须填写实际识别材质 observedMaterial");
        }
        if (conclusion == null || conclusion.isBlank()) throw ApiException.badRequest("质检结论不能为空");
        StoreQcRecord rec = qcRepo.findByItemId(item.getId()).orElseGet(StoreQcRecord::new);
        rec.setItemId(item.getId());
        rec.setOrderId(item.getOrderId());
        rec.setInspectorId(inspector.getId());
        rec.setObservedMaterial(observedMaterial);
        rec.setObservedAccessories(observedAccessories);
        rec.setConclusion(conclusion);
        rec.setPassed(passed);
        rec.setResponsibility(responsibility == null ? Responsibility.PENDING : responsibility);
        return qcRepo.save(rec);
    }

    /** 质检通过：衣物可进入待洗护确认态（订单内全部通过则订单 CONFIRMED） */
    @Transactional
    public GarmentItem passQc(UserAccount inspector, Long itemId, String observedMaterial,
                              String observedAccessories, String conclusion) {
        GarmentItem item = itemRepo.findById(itemId).orElseThrow(() -> ApiException.notFound("衣物不存在"));
        if (item.getStatus() != GarmentStatus.STORE_QC) {
            throw ApiException.conflict("衣物不在门店质检环节: " + item.getStatus());
        }
        StoreQcRecord rec = inspect(inspector, item, observedMaterial, observedAccessories,
                conclusion == null ? "质检通过，与订单一致" : conclusion, true, Responsibility.PENDING);
        sm.transit(item, GarmentStatus.CONFIRMED, "门店质检通过");
        itemRepo.save(item);
        timeline.add(item.getOrderId(), item.getId(), "QC",
                "门店质检通过：" + rec.getConclusion(), inspector, null);
        OrderFlowSupport.advanceIfNoHolds(orderRepo, itemRepo, sm, item.getOrderId());
        return item;
    }

    @Transactional(readOnly = true)
    public List<PickupRecord> pickupsOf(Long orderId) {
        return pickupRepo.findByOrderIdOrderByPickedAtAsc(orderId);
    }

    @Transactional(readOnly = true)
    public List<StoreQcRecord> qcOfOrder(Long orderId) {
        return qcRepo.findByOrderId(orderId);
    }
}
