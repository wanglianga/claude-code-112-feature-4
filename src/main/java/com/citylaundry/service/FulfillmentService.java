package com.citylaundry.service;

import com.citylaundry.common.ApiException;
import com.citylaundry.model.*;
import com.citylaundry.model.Enums.*;
import com.citylaundry.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 履约服务：拒绝承接后的骑手重派/回收、退回客户与原路退款、门店产能占用视图。
 *
 * <p>幂等：回收完成（RECLAIM 动作）与退回退款（RETURN 动作）都以 IdempotentRequest 去重，
 * 重复调用不会重复出库或重复退款。
 */
@Service
public class FulfillmentService {
    private final RiderReclaimRepo reclaimRepo;
    private final GarmentItemRepo itemRepo;
    private final LaundryOrderRepo orderRepo;
    private final GarmentPhotoRepo photoRepo;
    private final FactorySiteRepo siteRepo;
    private final PaymentRepo paymentRepo;
    private final PaymentService paymentService;
    private final GarmentStateMachine sm;
    private final IdempotentRequestRepo idemRepo;

    /** 占用洗护产能的在厂衣物状态（待异常决策/待回收的衣物不占用洗护产线产能） */
    private static final List<GarmentStatus> OCCUPYING = List.of(
            GarmentStatus.AT_FACTORY, GarmentStatus.EXTRA_APPROVED,
            GarmentStatus.PROCESS_ASSIGNED, GarmentStatus.IN_PROCESS,
            GarmentStatus.PROCESS_DONE, GarmentStatus.IN_REVIEW);

    public FulfillmentService(RiderReclaimRepo reclaimRepo, GarmentItemRepo itemRepo,
                              LaundryOrderRepo orderRepo, GarmentPhotoRepo photoRepo,
                              FactorySiteRepo siteRepo, PaymentRepo paymentRepo,
                              PaymentService paymentService, GarmentStateMachine sm,
                              IdempotentRequestRepo idemRepo) {
        this.reclaimRepo = reclaimRepo;
        this.itemRepo = itemRepo;
        this.orderRepo = orderRepo;
        this.photoRepo = photoRepo;
        this.siteRepo = siteRepo;
        this.paymentRepo = paymentRepo;
        this.paymentService = paymentService;
        this.sm = sm;
        this.idemRepo = idemRepo;
    }

    public List<RiderReclaim> tasks(ReclaimStatus status) {
        return status == null ? reclaimRepo.findAll() : reclaimRepo.findByStatusOrderByCreatedAtAsc(status);
    }

    /** 重派骑手：PENDING → ASSIGNED（重复指派同一任务幂等返回） */
    @Transactional
    public RiderReclaim assign(UserAccount actor, Long reclaimId, Long riderId, String note) {
        RiderReclaim r = getReclaim(reclaimId);
        if (r.getStatus() == ReclaimStatus.ASSIGNED && riderId != null && riderId.equals(r.getRiderId())) {
            return r;
        }
        if (r.getStatus() != ReclaimStatus.PENDING) {
            throw ApiException.conflict("回收任务不是待接单状态: " + r.getStatus());
        }
        r.setRiderId(riderId == null ? actor.getId() : riderId);
        r.setStatus(ReclaimStatus.ASSIGNED);
        r.setAssignedAt(LocalDateTime.now());
        if (note != null && !note.isBlank()) r.setNote(note);
        return reclaimRepo.save(r);
    }

    /** 骑手完成回收：必须留影；衣物离厂释放站点产能（幂等：重复回调只出库一次） */
    @Transactional
    public RiderReclaim completeReclaim(UserAccount rider, Long reclaimId, String handoverPhotoUrl, String note) {
        RiderReclaim r = getReclaim(reclaimId);
        String key = "RECLAIM:" + r.getId();
        if (idemRepo.findByActionAndIdempotencyKey("RECLAIM", key).isPresent()) {
            return r; // 重复回收请求幂等
        }
        if (r.getStatus() != ReclaimStatus.ASSIGNED && r.getStatus() != ReclaimStatus.PENDING) {
            throw ApiException.conflict("回收任务当前状态不能完成回收: " + r.getStatus());
        }
        if (handoverPhotoUrl == null || handoverPhotoUrl.isBlank()) {
            throw ApiException.badRequest("骑手回收必须上传交接留影 handoverPhotoUrl");
        }
        GarmentItem item = itemRepo.findById(r.getItemId()).orElseThrow();

        r.setStatus(ReclaimStatus.RECLAIMED);
        r.setHandoverPhotoUrl(handoverPhotoUrl);
        r.setReclaimedAt(LocalDateTime.now());
        r.setRiderId(r.getRiderId() == null ? rider.getId() : r.getRiderId());
        if (note != null && !note.isBlank()) r.setNote(note);
        r = reclaimRepo.save(r);

        GarmentPhoto photo = new GarmentPhoto();
        photo.setItemId(item.getId());
        photo.setStage(PhotoStage.RECLAIM);
        photo.setUrl(handoverPhotoUrl);
        photo.setUploadedBy(rider.getId());
        photo.setLocation(r.getReclaimAddress());
        photo.setTakenAt(LocalDateTime.now());
        photoRepo.save(photo);

        item.setReclaimId(r.getId());
        item.setCurrentSiteId(null); // 离厂：释放站点产能
        itemRepo.save(item);
        sm.transition(item.getId(), GarmentStatus.RECLAIMED, "RECLAIM_DONE",
                "RECLAIM", r.getId(), rider.getId(), "骑手回收留影完成，等待退回客户与退款");

        paymentService.recordIdempotent("RECLAIM", key, r.getId(), r.getReclaimNo());
        return r;
    }

    /** 退回客户并原路退回（幂等：同一件衣物只退一次）；衣物 → RETURNED */
    @Transactional
    public GarmentItem returnToCustomer(UserAccount actor, Long itemId, String note) {
        GarmentItem item = itemRepo.findById(itemId).orElseThrow(() -> ApiException.notFound("衣物不存在"));
        String key = "RETURN:" + itemId;
        if (idemRepo.findByActionAndIdempotencyKey("RETURN", key).isPresent()) {
            return item; // 幂等
        }
        if (item.getStatus() != GarmentStatus.RECLAIMED) {
            throw ApiException.conflict("衣物未完成骑手回收，不能退回: " + item.getStatus());
        }

        // 逐支付单退回/释放（基础预授权 + 追加预授权），各自幂等
        for (Payment p : paymentRepo.findByItemIdOrderByCreatedAtAsc(itemId)) {
            if (p.getStatus() == PaymentStatus.CAPTURED || p.getStatus() == PaymentStatus.PARTIALLY_REFUNDED) {
                paymentService.refund(p.getId(), null, "refund-" + p.getId());
            } else if (p.getStatus() == PaymentStatus.AUTHORIZED || p.getStatus() == PaymentStatus.PENDING
                    || p.getStatus() == PaymentStatus.FAILED) {
                paymentService.refund(p.getId(), null, "release-" + p.getId());
            }
        }
        item.setAgreedExtra(java.math.BigDecimal.ZERO);
        item.setAgreedQuoteVersionId(null);
        itemRepo.save(item);

        GarmentItem moved = sm.transition(itemId, GarmentStatus.RETURNED, "RETURN_TO_CUSTOMER",
                "ITEM", itemId, actor.getId(), "已退回客户并完成原路退回：" + note);
        paymentService.recordIdempotent("RETURN", key, itemId, moved.getItemCode());
        aggregateOrder(item.getOrderId());
        return moved;
    }

    /** 全部衣物进入就绪/拒洗/退回终态后，按是否存在退回件聚合订单交付状态 */
    private void aggregateOrder(Long orderId) {
        List<GarmentItem> items = itemRepo.findByOrderIdOrderByIdAsc(orderId);
        boolean allTerminal = !items.isEmpty() && items.stream().allMatch(i ->
                i.getStatus() == GarmentStatus.READY_FOR_DELIVERY || i.getStatus() == GarmentStatus.REJECTED
                        || i.getStatus() == GarmentStatus.RETURNED || i.getStatus() == GarmentStatus.DELIVERED);
        if (!allTerminal) return;
        boolean anyReturned = items.stream().anyMatch(i -> i.getStatus() == GarmentStatus.RETURNED);
        LaundryOrder order = orderRepo.findById(orderId).orElseThrow();
        if (order.getStatus() != OrderStatus.DELIVERED && order.getStatus() != OrderStatus.SETTLED) {
            order.setStatus(anyReturned ? OrderStatus.PARTIALLY_RETURNED : OrderStatus.READY_FOR_DELIVERY);
            orderRepo.save(order);
        }
    }

    public RiderReclaim getReclaim(Long id) {
        return reclaimRepo.findById(id).orElseThrow(() -> ApiException.notFound("回收任务不存在"));
    }

    /** 门店/站点产能占用视图（在厂件数 / 日上限） */
    @Transactional(readOnly = true)
    public Map<String, Object> capacity(Long siteId) {
        FactorySite site = siteId == null ? null : siteRepo.findById(siteId).orElse(null);
        Map<String, Object> resp = new LinkedHashMap<>();
        if (site != null) {
            resp.put("siteId", site.getId());
            resp.put("siteName", site.getName());
            resp.put("dailyCapacity", site.getDailyCapacity());
        }
        int occupied = 0;
        for (GarmentStatus s : OCCUPYING) {
            List<GarmentItem> list = siteId == null
                    ? itemRepo.findByStatusOrderByIdAsc(s)
                    : itemRepo.findByCurrentSiteIdAndStatusOrderByIdAsc(siteId, s);
            occupied += list.size();
        }
        resp.put("occupied", occupied);
        if (site != null) resp.put("available", Math.max(0, site.getDailyCapacity() - occupied));
        return resp;
    }
}
