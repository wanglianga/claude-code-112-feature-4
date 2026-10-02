package com.citylaundry.service;

import com.citylaundry.model.Enums.GarmentStatus;
import com.citylaundry.model.Enums.OrderStatus;
import com.citylaundry.model.GarmentItem;
import com.citylaundry.model.LaundryOrder;
import com.citylaundry.repo.GarmentItemRepo;
import com.citylaundry.repo.LaundryOrderRepo;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 订单级状态聚合：订单是否被异常/回收挂起，由【全部衣物】的状态推导。
 * 任何一件衣物仍在异常决策或回收链路上，订单都不能进入洗护/结算。
 */
public final class OrderFlowSupport {
    private OrderFlowSupport() {}

    /** 异常决策/回收挂起（会阻止洗护与自动结算） */
    private static final Set<GarmentStatus> EXCEPTION_HOLDS = EnumSet.of(
            GarmentStatus.QC_MISMATCH, GarmentStatus.EXCEPTION_PENDING, GarmentStatus.AWAIT_QUOTE,
            GarmentStatus.AWAIT_CUSTOMER, GarmentStatus.AWAIT_MANUAL_REVIEW,
            GarmentStatus.REJECTED, GarmentStatus.RECOVERY_SCHEDULED);

    /** 取件/质检进行中（尚未得出结论，不属于异常） */
    private static final Set<GarmentStatus> QC_IN_PROGRESS = EnumSet.of(
            GarmentStatus.PICKED_UP, GarmentStatus.STORE_QC);

    private static final Set<GarmentStatus> IN_FACTORY_PATH = EnumSet.of(
            GarmentStatus.AT_FACTORY, GarmentStatus.PROCESS_ASSIGNED, GarmentStatus.IN_PROCESS,
            GarmentStatus.PROCESS_DONE, GarmentStatus.IN_REVIEW,
            GarmentStatus.READY_FOR_DELIVERY, GarmentStatus.DELIVERED, GarmentStatus.CLOSED);

    /** 质检/决策终态（允许订单进入 CONFIRMED 送厂） */
    private static final Set<GarmentStatus> CONFIRMED_PATH = EnumSet.of(
            GarmentStatus.CONFIRMED, GarmentStatus.QUOTE_CONFIRMED, GarmentStatus.BASIC_ONLY);

    public static boolean exceptionHold(GarmentStatus s) {
        return EXCEPTION_HOLDS.contains(s);
    }

    /** 依据衣物状态推进订单（不做非法越级；状态机兜底拒绝） */
    public static void advanceIfNoHolds(LaundryOrderRepo orderRepo, GarmentItemRepo itemRepo,
                                        GarmentStateMachine sm, Long orderId) {
        LaundryOrder order = orderRepo.findById(orderId).orElse(null);
        if (order == null) return;
        List<GarmentItem> items = itemRepo.findByOrderIdOrderByIdAsc(orderId);
        if (items.isEmpty()) return;

        boolean anyException = items.stream().anyMatch(i -> EXCEPTION_HOLDS.contains(i.getStatus()));
        boolean anyInFactory = items.stream().anyMatch(i -> IN_FACTORY_PATH.contains(i.getStatus()));
        boolean anyQc = items.stream().anyMatch(i -> QC_IN_PROGRESS.contains(i.getStatus()));
        boolean anyConfirmed = items.stream().anyMatch(i -> CONFIRMED_PATH.contains(i.getStatus()));
        boolean allReturned = items.stream().allMatch(i ->
                i.getStatus() == GarmentStatus.RETURNED || i.getStatus() == GarmentStatus.RECOVERED
                        || i.getStatus() == GarmentStatus.CLOSED);

        // 1) 异常优先：未入厂时挂到 EXCEPTION_PENDING
        if (anyException && !anyInFactory) {
            if (order.getStatus() != OrderStatus.EXCEPTION_PENDING
                    && order.getStatus() != OrderStatus.MANUAL_REVIEW
                    && sm.canOrder(order.getStatus(), OrderStatus.EXCEPTION_PENDING)) {
                order.setStatus(OrderStatus.EXCEPTION_PENDING);
                orderRepo.save(order);
            }
            return;
        }
        // 2) 已入厂：订单进入 IN_FACTORY（在途异常不回退订单状态）
        if (anyInFactory) {
            if (order.getStatus() != OrderStatus.IN_FACTORY
                    && sm.canOrder(order.getStatus(), OrderStatus.IN_FACTORY)) {
                order.setStatus(OrderStatus.IN_FACTORY);
                orderRepo.save(order);
            }
            return;
        }
        // 3) 全部退回：不送厂，保留 EXCEPTION_PENDING 等待人工/关单结算
        if (allReturned) {
            return;
        }
        // 4) 质检进行中：至少一件已到店则订单 STORE_QC
        if (anyQc) {
            boolean anyAtStore = items.stream().anyMatch(i -> i.getStatus() == GarmentStatus.STORE_QC);
            if (anyAtStore && order.getStatus() != OrderStatus.STORE_QC
                    && sm.canOrder(order.getStatus(), OrderStatus.STORE_QC)) {
                order.setStatus(OrderStatus.STORE_QC);
                orderRepo.save(order);
            }
            return;
        }
        // 5) 全部完成质检/决策 → CONFIRMED
        if (anyConfirmed && sm.canOrder(order.getStatus(), OrderStatus.CONFIRMED)) {
            order.setStatus(OrderStatus.CONFIRMED);
            orderRepo.save(order);
        }
    }
}
