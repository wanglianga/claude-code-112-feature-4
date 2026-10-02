package com.citylaundry.service;

import com.citylaundry.common.ApiException;
import com.citylaundry.model.*;
import com.citylaundry.model.Enums.*;
import com.citylaundry.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 骑手回收（重派）：拒绝承接 / 客户选择退回时安排骑手取回衣物。
 * 重派幂等：同一异常+同一原因的未取消任务唯一，重复重派返回原任务，
 * 不会重复出库、不会重复派单。
 */
@Service
public class RecoveryService {
    private final RecoveryTaskRepo taskRepo;
    private final GarmentItemRepo itemRepo;
    private final LaundryOrderRepo orderRepo;
    private final GarmentPhotoRepo photoRepo;
    private final UserAccountRepo userRepo;
    private final PickupRecordRepo pickupRepo;
    private final GarmentStateMachine sm;
    private final TimelineService timeline;

    public RecoveryService(RecoveryTaskRepo taskRepo, GarmentItemRepo itemRepo,
                           LaundryOrderRepo orderRepo, GarmentPhotoRepo photoRepo,
                           UserAccountRepo userRepo, PickupRecordRepo pickupRepo,
                           GarmentStateMachine sm, TimelineService timeline) {
        this.taskRepo = taskRepo;
        this.itemRepo = itemRepo;
        this.orderRepo = orderRepo;
        this.photoRepo = photoRepo;
        this.userRepo = userRepo;
        this.pickupRepo = pickupRepo;
        this.sm = sm;
        this.timeline = timeline;
    }

    /**
     * 生成/复用回收任务（幂等）。
     * @param keySuffix 幂等域，一般用 异常id+原因；同一域重复调用返回原任务
     */
    @Transactional
    public RecoveryTask dispatch(Long itemId, Long exceptionId, String reason, String idemSuffix) {
        GarmentItem item = itemRepo.findById(itemId).orElseThrow(() -> ApiException.notFound("衣物不存在"));
        LaundryOrder order = orderRepo.findById(item.getOrderId()).orElseThrow();
        String key = "rec-" + itemId + "-" + (idemSuffix == null ? reason : idemSuffix);

        RecoveryTask existing = taskRepo.findByIdempotencyKey(key).orElse(null);
        if (existing != null && existing.getStatus() != RecoveryStatus.CANCELLED) {
            timeline.add(order.getId(), itemId, "RECOVERY",
                    "重复回收请求命中幂等，复用原任务 " + existing.getTaskNo(), 0L, "SYSTEM",
                    existing.getTaskNo());
            return existing;
        }

        UserAccount rider = userRepo.findById(itemRiderId(order)).orElseThrow();
        RecoveryTask t = new RecoveryTask();
        t.setItemId(itemId);
        t.setOrderId(order.getId());
        t.setExceptionId(exceptionId);
        t.setReason(reason);
        t.setRiderId(rider.getId());
        t.setReturnAddress(order.getDeliveryAddress());
        t.setStatus(RecoveryStatus.DISPATCHED);
        t.setDispatchedAt(LocalDateTime.now());
        t.setIdempotencyKey(key);
        t.setTaskNo("TMP");
        t = taskRepo.save(t);
        t.setTaskNo("R" + String.format("%08d", t.getId()));
        t = taskRepo.saveAndFlush(t);

        sm.transit(item, GarmentStatus.RECOVERY_SCHEDULED, reason);
        itemRepo.save(item);
        timeline.add(order.getId(), itemId, "RECOVERY",
                "生成骑手回收任务 " + t.getTaskNo() + "（原因=" + reason + "，退回地址="
                        + t.getReturnAddress() + "）", rider, t.getTaskNo());
        return t;
    }

    /** 骑手完成回收：留影、衣物 RECOVERED→RETURNED */
    @Transactional
    public RecoveryTask complete(UserAccount rider, Long taskId, String handoverPhotoUrl) {
        RecoveryTask t = taskRepo.findById(taskId).orElseThrow(() -> ApiException.notFound("回收任务不存在"));
        if (!t.getRiderId().equals(rider.getId())) {
            throw ApiException.forbidden("只能完成分配给自己的回收任务");
        }
        if (t.getStatus() == RecoveryStatus.DONE) return t; // 幂等
        if (handoverPhotoUrl == null || handoverPhotoUrl.isBlank()) {
            throw ApiException.badRequest("回收完成必须上传交接留影 handoverPhotoUrl");
        }
        GarmentItem item = itemRepo.findById(t.getItemId()).orElseThrow();
        t.setStatus(RecoveryStatus.DONE);
        t.setDoneAt(LocalDateTime.now());
        t.setHandoverPhotoUrl(handoverPhotoUrl);
        taskRepo.save(t);

        GarmentPhoto photo = new GarmentPhoto();
        photo.setItemId(item.getId());
        photo.setStage(PhotoStage.DELIVERY);
        photo.setUrl(handoverPhotoUrl);
        photo.setUploadedBy(rider.getId());
        photoRepo.save(photo);

        sm.transit(item, GarmentStatus.RECOVERED, "骑手回收完成");
        sm.transit(item, GarmentStatus.RETURNED, "衣物退回客户");
        itemRepo.save(item);

        timeline.add(t.getOrderId(), item.getId(), "RECOVERY",
                "骑手[" + rider.getName() + "]完成回收退回并留影", rider, t.getTaskNo());
        OrderFlowSupport.advanceIfNoHolds(orderRepo, itemRepo, sm, t.getOrderId());
        return t;
    }

    @Transactional(readOnly = true)
    public List<RecoveryTask> riderTasks(Long riderId) {
        return taskRepo.findByRiderIdAndStatusOrderByCreatedAtAsc(riderId, RecoveryStatus.DISPATCHED);
    }

    @Transactional(readOnly = true)
    public List<RecoveryTask> openTasks() {
        return taskRepo.findByStatusOrderByCreatedAtAsc(RecoveryStatus.DISPATCHED);
    }

    private Long itemRiderId(LaundryOrder order) {
        return pickupRepo.findFirstByOrderIdOrderByPickedAtAsc(order.getId())
                .map(PickupRecord::getRiderId)
                .orElseGet(() -> userRepo.findFirstByRole(Role.PICKUP)
                        .orElseThrow(() -> ApiException.badRequest("系统中没有骑手账号")).getId());
    }
}
