package com.citylaundry.model;

import com.citylaundry.model.Enums.PaymentStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付单：订单维度预授权 + 按确认结果请款 + 原路退回。
 * 支付回调以 PaymentEvent.idempotencyKey 去重，重复回调不重复扣款/退款。
 */
@Getter @Setter
@Entity @Table(name = "payment_order")
public class PaymentOrder {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String paymentNo;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long customerId;

    @Column(nullable = false)
    private Long cardId;

    /** 预授权金额（下单估价冻结） */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal preauthAmount = BigDecimal.ZERO;

    /** 已请款金额 */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal capturedAmount = BigDecimal.ZERO;

    /** 已退款金额 */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal refundedAmount = BigDecimal.ZERO;

    /** 已释放的预授权金额（防重复释放：剩余冻结=预授权-已请款-已释放） */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal releasedAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentStatus status = PaymentStatus.CREATED;

    /** 外部渠道交易号（演示模拟） */
    @Column(length = 64)
    private String channelTxNo;

    /** 最近一次回调是否失败（失败保留 FAILED，可重试，不影响已请款幂等性） */
    @Column(length = 512)
    private String lastCallbackError;

    private LocalDateTime preauthedAt;
    private LocalDateTime capturedAt;
    private LocalDateTime refundedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
