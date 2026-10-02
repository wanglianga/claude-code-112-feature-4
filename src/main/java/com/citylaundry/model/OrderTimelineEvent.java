package com.citylaundry.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 订单档案时间线事件：取洗档案上发生的每一个可审计动作。
 * 只增不改（append-only），供客服、门店、客户核对同一事实。
 */
@Getter @Setter
@Entity @Table(name = "order_timeline_event")
public class OrderTimelineEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    private Long itemId; // 订单级事件可空

    /** 事件类型：PICKUP / QC / EXCEPTION / EVIDENCE / QUOTE_ISSUED / FEEDBACK /
     *  STATE_CHANGE / PAYMENT / REFUND / RECOVERY / SCHEDULE / CLOSEOUT ... */
    @Column(nullable = false, length = 40)
    private String type;

    @Column(nullable = false, length = 1024)
    private String detail;

    /** 操作人（0=系统） */
    @Column(nullable = false)
    private Long actorId;

    @Column(nullable = false, length = 32)
    private String actorRole;

    /** 关联实体引用（如报价单号/支付单号/异常号），便于跳转核对 */
    @Column(length = 64)
    private String refNo;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
