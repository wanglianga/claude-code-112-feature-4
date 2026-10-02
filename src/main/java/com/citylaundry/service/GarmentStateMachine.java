package com.citylaundry.service;

import com.citylaundry.common.ApiException;
import com.citylaundry.model.Enums.GarmentStatus;
import com.citylaundry.model.GarmentItem;
import com.citylaundry.model.GarmentStatusEvent;
import com.citylaundry.repo.GarmentItemRepo;
import com.citylaundry.repo.GarmentStatusEventRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 衣物级状态机：集中定义每件衣物的合法迁移路径，任何服务不得绕过它直接改状态。
 * 每次迁移落一条不可变 {@link GarmentStatusEvent}，保证全程可审计、可回放。
 */
@Service
public class GarmentStateMachine {

    private static final Map<GarmentStatus, Set<GarmentStatus>> ALLOWED = new EnumMap<>(GarmentStatus.class);

    static {
        allow(GarmentStatus.REGISTERED, GarmentStatus.PHOTOGRAPHED, GarmentStatus.REJECTED,
                GarmentStatus.ANOMALY_QUOTED); // 骑手取件现场发现异常
        allow(GarmentStatus.PHOTOGRAPHED, GarmentStatus.CONFIRMED, GarmentStatus.REGISTERED,
                GarmentStatus.REJECTED, GarmentStatus.ANOMALY_QUOTED);
        allow(GarmentStatus.CONFIRMED, GarmentStatus.AT_FACTORY, GarmentStatus.REJECTED,
                GarmentStatus.ANOMALY_QUOTED);
        allow(GarmentStatus.AT_FACTORY,
                GarmentStatus.PROCESS_ASSIGNED, GarmentStatus.ANOMALY_QUOTED, GarmentStatus.REJECTED);
        // 异常报价分支
        allow(GarmentStatus.ANOMALY_QUOTED,
                GarmentStatus.MANUAL_REVIEW,        // 客户申请人工复核
                GarmentStatus.EXTRA_APPROVED,       // 客户确认追加报价
                GarmentStatus.RECLAIM_PENDING,      // 客户拒绝承接→退回
                GarmentStatus.PROCESS_ASSIGNED,     // 维持原价/仅基础处理后直接恢复排产
                GarmentStatus.AT_FACTORY,           // 恢复到异常前业务状态
                GarmentStatus.IN_PROCESS,           // 洗护中异常、不加价继续
                GarmentStatus.PHOTOGRAPHED, GarmentStatus.REGISTERED, // 取件阶段异常解决后恢复收衣流
                GarmentStatus.REJECTED);
        allow(GarmentStatus.MANUAL_REVIEW, GarmentStatus.ANOMALY_QUOTED); // 客服裁决出新版本后回到客户决策
        // 客户确认追加后：可撤回（洗护未开始）或继续排产/恢复收衣流/继续洗护
        allow(GarmentStatus.EXTRA_APPROVED,
                GarmentStatus.ANOMALY_QUOTED,       // 客户确认后撤回
                GarmentStatus.AT_FACTORY,           // 入厂
                GarmentStatus.CONFIRMED,            // 取件阶段确认追加后，顾客确认收衣
                GarmentStatus.PROCESS_ASSIGNED,     // 入厂后进入排产
                GarmentStatus.IN_PROCESS,           // 工艺已分配时恢复洗护
                GarmentStatus.PHOTOGRAPHED, GarmentStatus.REGISTERED); // 取件阶段确认后恢复
        // 回收流
        allow(GarmentStatus.RECLAIM_PENDING,
                GarmentStatus.RECLAIMED, GarmentStatus.ANOMALY_QUOTED); // 客户改变主意（任务取消）
        allow(GarmentStatus.RECLAIMED, GarmentStatus.RETURNED);
        // 常规洗护流
        allow(GarmentStatus.PROCESS_ASSIGNED,
                GarmentStatus.IN_PROCESS, GarmentStatus.AT_FACTORY, GarmentStatus.REJECTED);
        allow(GarmentStatus.IN_PROCESS,
                GarmentStatus.PROCESS_DONE, GarmentStatus.ANOMALY_QUOTED); // 洗护中发现新异常
        allow(GarmentStatus.PROCESS_DONE, GarmentStatus.IN_REVIEW, GarmentStatus.READY_FOR_DELIVERY);
        allow(GarmentStatus.IN_REVIEW, GarmentStatus.READY_FOR_DELIVERY);
        allow(GarmentStatus.READY_FOR_DELIVERY, GarmentStatus.DELIVERED);
        allow(GarmentStatus.DELIVERED, GarmentStatus.CLOSED);
    }

    private static void allow(GarmentStatus from, GarmentStatus... tos) {
        ALLOWED.put(from, EnumSet.copyOf(java.util.List.of(tos)));
    }

    private final GarmentItemRepo itemRepo;
    private final GarmentStatusEventRepo eventRepo;

    public GarmentStateMachine(GarmentItemRepo itemRepo, GarmentStatusEventRepo eventRepo) {
        this.itemRepo = itemRepo;
        this.eventRepo = eventRepo;
    }

    public boolean canTransition(GarmentStatus from, GarmentStatus to) {
        return ALLOWED.getOrDefault(from, EnumSet.noneOf(GarmentStatus.class)).contains(to);
    }

    /** 执行状态迁移并记录事件；非法迁移抛 409（状态机是排产/回收/退款等幂等动作的硬门禁） */
    @Transactional
    public GarmentItem transition(Long itemId, GarmentStatus to, String event,
                                  String refType, Long refId, Long operatorId, String note) {
        GarmentItem item = itemRepo.findById(itemId)
                .orElseThrow(() -> ApiException.notFound("衣物不存在"));
        GarmentStatus from = item.getStatus();
        if (from == to) {
            // 幂等重入：同一目标状态直接返回，不重复记事件（调用方负责业务幂等）
            return item;
        }
        if (!canTransition(from, to)) {
            throw ApiException.conflict("衣物[" + item.getItemCode() + "]状态机不允许从 "
                    + from + " 迁移到 " + to + "（事件:" + event + "）");
        }
        item.setStatus(to);
        item = itemRepo.save(item);

        GarmentStatusEvent e = new GarmentStatusEvent();
        e.setItemId(itemId);
        e.setFromStatus(from.name());
        e.setToStatus(to.name());
        e.setEvent(event);
        e.setRefType(refType);
        e.setRefId(refId);
        e.setOperatorId(operatorId == null ? 0L : operatorId);
        e.setNote(note);
        eventRepo.save(e);
        return item;
    }
}
