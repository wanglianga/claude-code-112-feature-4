package com.citylaundry.service;

import com.citylaundry.common.ApiException;
import com.citylaundry.model.Enums.GarmentStatus;
import com.citylaundry.model.Enums.OrderStatus;
import com.citylaundry.model.GarmentItem;
import com.citylaundry.model.LaundryOrder;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 衣物级状态机：所有衣物状态迁移的唯一入口。
 * 非法迁移直接拒绝，保证异常挂起、撤回、回收、洗护等环节不会跳步或覆盖。
 */
@Component
public class GarmentStateMachine {

    private static final Map<GarmentStatus, Set<GarmentStatus>> ALLOWED = new EnumMap<>(GarmentStatus.class);

    static {
        allow(GarmentStatus.REGISTERED, GarmentStatus.PHOTOGRAPHED, GarmentStatus.EXCEPTION_PENDING);
        allow(GarmentStatus.PHOTOGRAPHED, GarmentStatus.PICKED_UP, GarmentStatus.EXCEPTION_PENDING);
        allow(GarmentStatus.PICKED_UP, GarmentStatus.STORE_QC, GarmentStatus.EXCEPTION_PENDING, GarmentStatus.RECOVERED);
        allow(GarmentStatus.STORE_QC, GarmentStatus.CONFIRMED, GarmentStatus.QC_MISMATCH, GarmentStatus.EXCEPTION_PENDING);
        allow(GarmentStatus.QC_MISMATCH, GarmentStatus.AWAIT_QUOTE, GarmentStatus.AWAIT_CUSTOMER,
                GarmentStatus.EXCEPTION_PENDING, GarmentStatus.CONFIRMED);
        allow(GarmentStatus.EXCEPTION_PENDING, GarmentStatus.AWAIT_QUOTE, GarmentStatus.AWAIT_CUSTOMER,
                GarmentStatus.AWAIT_MANUAL_REVIEW, GarmentStatus.CONFIRMED, GarmentStatus.REJECTED);
        allow(GarmentStatus.AWAIT_QUOTE, GarmentStatus.AWAIT_CUSTOMER, GarmentStatus.AWAIT_MANUAL_REVIEW,
                GarmentStatus.EXCEPTION_PENDING);
        // 同一报价位可被新版本重新下发（SUPERSEDED 后新轮次 ISSUED）
        allow(GarmentStatus.AWAIT_CUSTOMER, GarmentStatus.AWAIT_CUSTOMER, GarmentStatus.QUOTE_CONFIRMED,
                GarmentStatus.BASIC_ONLY, GarmentStatus.REJECTED, GarmentStatus.AWAIT_MANUAL_REVIEW,
                GarmentStatus.EXCEPTION_PENDING, GarmentStatus.CONFIRMED, GarmentStatus.READY_FOR_DELIVERY);
        allow(GarmentStatus.AWAIT_MANUAL_REVIEW, GarmentStatus.AWAIT_CUSTOMER, GarmentStatus.QUOTE_CONFIRMED,
                GarmentStatus.BASIC_ONLY, GarmentStatus.REJECTED, GarmentStatus.CONFIRMED);
        // 客户确认后、排产/请款前允许撤回
        allow(GarmentStatus.QUOTE_CONFIRMED, GarmentStatus.CONFIRMED, GarmentStatus.EXCEPTION_PENDING,
                GarmentStatus.AT_FACTORY);
        allow(GarmentStatus.BASIC_ONLY, GarmentStatus.CONFIRMED, GarmentStatus.AT_FACTORY,
                GarmentStatus.EXCEPTION_PENDING, GarmentStatus.REJECTED);
        allow(GarmentStatus.CONFIRMED, GarmentStatus.AT_FACTORY, GarmentStatus.EXCEPTION_PENDING,
                GarmentStatus.REJECTED, GarmentStatus.RETURNED);
        allow(GarmentStatus.AT_FACTORY, GarmentStatus.PROCESS_ASSIGNED, GarmentStatus.REJECTED,
                GarmentStatus.EXCEPTION_PENDING);
        allow(GarmentStatus.PROCESS_ASSIGNED, GarmentStatus.IN_PROCESS, GarmentStatus.AT_FACTORY,
                GarmentStatus.REJECTED, GarmentStatus.EXCEPTION_PENDING);
        allow(GarmentStatus.IN_PROCESS, GarmentStatus.PROCESS_DONE, GarmentStatus.EXCEPTION_PENDING);
        allow(GarmentStatus.PROCESS_DONE, GarmentStatus.IN_REVIEW, GarmentStatus.READY_FOR_DELIVERY,
                GarmentStatus.EXCEPTION_PENDING);
        allow(GarmentStatus.IN_REVIEW, GarmentStatus.READY_FOR_DELIVERY, GarmentStatus.EXCEPTION_PENDING);
        allow(GarmentStatus.READY_FOR_DELIVERY, GarmentStatus.DELIVERED, GarmentStatus.RECOVERY_SCHEDULED,
                GarmentStatus.EXCEPTION_PENDING);
        allow(GarmentStatus.REJECTED, GarmentStatus.RECOVERY_SCHEDULED, GarmentStatus.AWAIT_CUSTOMER,
                GarmentStatus.CONFIRMED);
        allow(GarmentStatus.RECOVERY_SCHEDULED, GarmentStatus.RECOVERED, GarmentStatus.REJECTED);
        allow(GarmentStatus.DELIVERED, GarmentStatus.CLOSED, GarmentStatus.RECOVERY_SCHEDULED);
        allow(GarmentStatus.RECOVERED, GarmentStatus.RETURNED, GarmentStatus.CLOSED);
        allow(GarmentStatus.RETURNED, GarmentStatus.CLOSED);
        allow(GarmentStatus.CLOSED);
    }

    private static void allow(GarmentStatus from, GarmentStatus... tos) {
        ALLOWED.put(from, tos.length == 0 ? EnumSet.noneOf(GarmentStatus.class) : EnumSet.of(tos[0],
                java.util.Arrays.copyOfRange(tos, 1, tos.length)));
    }

    public boolean canTransfer(GarmentStatus from, GarmentStatus to) {
        Set<GarmentStatus> tos = ALLOWED.get(from);
        return tos != null && tos.contains(to);
    }

    public boolean canOrder(OrderStatus from, OrderStatus to) {
        Set<OrderStatus> tos = ORDER_ALLOWED.get(from);
        return tos != null && tos.contains(to);
    }

    /** 迁移并返回目标状态；非法迁移抛 409。 */
    public GarmentItem transit(GarmentItem item, GarmentStatus to, String reason) {
        GarmentStatus from = item.getStatus();
        if (from == to) {
            // 幂等：相同状态重复迁移直接放行（调用方负责避免重复副作用）
            return item;
        }
        if (!canTransfer(from, to)) {
            throw ApiException.conflict("衣物[" + item.getItemCode() + "]状态机拒绝迁移: "
                    + from + " → " + to + (reason == null ? "" : "（" + reason + "）"));
        }
        item.setStatus(to);
        return item;
    }

    /** 订单级状态校验（订单状态较少，集中声明） */
    private static final Map<OrderStatus, Set<OrderStatus>> ORDER_ALLOWED = new EnumMap<>(OrderStatus.class);

    static {
        oallow(OrderStatus.CREATED, OrderStatus.PICKED_UP, OrderStatus.CANCELLED);
        oallow(OrderStatus.PICKED_UP, OrderStatus.STORE_QC, OrderStatus.EXCEPTION_PENDING, OrderStatus.CONFIRMED);
        oallow(OrderStatus.STORE_QC, OrderStatus.EXCEPTION_PENDING, OrderStatus.CONFIRMED, OrderStatus.IN_FACTORY);
        oallow(OrderStatus.EXCEPTION_PENDING, OrderStatus.CONFIRMED, OrderStatus.STORE_QC,
                OrderStatus.IN_FACTORY, OrderStatus.MANUAL_REVIEW, OrderStatus.SETTLING,
                OrderStatus.CANCELLED);
        oallow(OrderStatus.CONFIRMED, OrderStatus.IN_FACTORY, OrderStatus.EXCEPTION_PENDING, OrderStatus.CANCELLED);
        oallow(OrderStatus.IN_FACTORY, OrderStatus.READY_FOR_DELIVERY, OrderStatus.EXCEPTION_PENDING,
                OrderStatus.MANUAL_REVIEW);
        oallow(OrderStatus.READY_FOR_DELIVERY, OrderStatus.DELIVERED, OrderStatus.EXCEPTION_PENDING);
        oallow(OrderStatus.DELIVERED, OrderStatus.SETTLING, OrderStatus.MANUAL_REVIEW, OrderStatus.CLOSED);
        oallow(OrderStatus.SETTLING, OrderStatus.MANUAL_REVIEW, OrderStatus.CLOSED, OrderStatus.DELIVERED);
        oallow(OrderStatus.MANUAL_REVIEW, OrderStatus.SETTLING, OrderStatus.CLOSED, OrderStatus.EXCEPTION_PENDING,
                OrderStatus.DELIVERED);
        oallow(OrderStatus.CLOSED);
        oallow(OrderStatus.CANCELLED);
    }

    private static void oallow(OrderStatus from, OrderStatus... tos) {
        ORDER_ALLOWED.put(from, tos.length == 0 ? EnumSet.noneOf(OrderStatus.class)
                : EnumSet.of(tos[0], java.util.Arrays.copyOfRange(tos, 1, tos.length)));
    }

    public LaundryOrder transitOrder(LaundryOrder order, OrderStatus to, String reason) {
        OrderStatus from = order.getStatus();
        if (from == to) return order;
        Set<OrderStatus> tos = ORDER_ALLOWED.get(from);
        if (tos == null || !tos.contains(to)) {
            throw ApiException.conflict("订单[" + order.getOrderNo() + "]状态机拒绝迁移: "
                    + from + " → " + to + (reason == null ? "" : "（" + reason + "）"));
        }
        order.setStatus(to);
        return order;
    }

    /** 衣物是否仍挂在异常决策链路上（未最终放行洗护/退回） */
    public static boolean isHeldByException(GarmentStatus s) {
        return s == GarmentStatus.EXCEPTION_PENDING || s == GarmentStatus.AWAIT_QUOTE
                || s == GarmentStatus.AWAIT_CUSTOMER || s == GarmentStatus.AWAIT_MANUAL_REVIEW
                || s == GarmentStatus.QC_MISMATCH;
    }
}
