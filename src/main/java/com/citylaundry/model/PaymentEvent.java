package com.citylaundry.model;

import com.citylaundry.model.Enums.PaymentStatus;
import com.citylaundry.model.Enums.PaymentType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 支付回调事件：渠道回调原样落库，以 idempotencyKey 幂等。
 * 衣物已进入洗护但回调失败时事件记 FAILED，可重放；
 * 重放成功不产生第二次扣款。
 */
@Getter @Setter
@Entity @Table(name = "payment_event",
        uniqueConstraints = @UniqueConstraint(columnNames = "idempotency_key"))
public class PaymentEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long paymentId;

    @Column(nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentType type;

    /** 渠道幂等键（同一笔渠道回调重试用同一 key） */
    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    /** 渠道交易号 */
    @Column(length = 64)
    private String channelTxNo;

    /** 本事件资金金额（请款/退款金额） */
    @Column(nullable = false, precision = 12, scale = 2)
    private java.math.BigDecimal amount = java.math.BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentStatus result;

    /** 回调是否声明失败（模拟衣物已洗护但支付回调失败） */
    @Column(nullable = false)
    private boolean callbackFailed = false;

    @Column(length = 512)
    private String errorMessage;

    @Column(nullable = false)
    private LocalDateTime receivedAt = LocalDateTime.now();

    /** 是否已应用到账户（重复回调直接跳过） */
    @Column(nullable = false)
    private boolean applied = false;

    private LocalDateTime appliedAt;
}
