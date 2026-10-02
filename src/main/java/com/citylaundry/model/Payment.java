package com.citylaundry.model;

import com.citylaundry.model.Enums.PaymentIntent;
import com.citylaundry.model.Enums.PaymentStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付单：一件衣物一条支付链路（预授权 → 扣款 → 回调确认；拒绝承接 → 释放/退款）。
 *
 * <p>幂等：
 * <ul>
 *   <li>{@link #requestId} 为客户端幂等键，唯一约束防止重复建单；</li>
 *   <li>{@link #callbackId} 为支付渠道回调流水号，回调重复投递只处理一次；</li>
 *   <li>{@link #captureIdempotencyKey} 防止洗护已开始后扣款被重复执行（重复出库/扣款）。</li>
 * </ul>
 * 衣物已进入洗护但支付回调失败：停留在 CAPTURE 中间态，可重试回调，衣物不回退状态。
 */
@Getter @Setter
@Entity @Table(name = "payment")
public class Payment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 48)
    private String paymentNo;

    @Column(nullable = false)
    private Long itemId;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long cardId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentIntent intent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private PaymentStatus status = PaymentStatus.CREATED;

    /** 预授权/扣款金额 */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    /** 已实际扣款金额（回调成功后落账） */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal capturedAmount = BigDecimal.ZERO;

    /** 本次请求扣款金额（支持部分扣款：实际工艺价低于预授权时只扣该金额，差额释放） */
    @Column(precision = 12, scale = 2)
    private BigDecimal pendingCaptureAmount;

    /** 已退款金额 */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal refundedAmount = BigDecimal.ZERO;

    /** 建单幂等键（同一业务请求重复提交不重复冻结） */
    @Column(nullable = false, unique = true, length = 64)
    private String requestId;

    /** 业务引用，如 BASE:itemId / EXTRA:quoteVersionId */
    @Column(length = 64)
    private String bizRef;

    /** 渠道回调流水号（重复回调幂等） */
    @Column(unique = true, length = 64)
    private String callbackId;

    /** 扣款执行幂等键（防止重复扣款） */
    @Column(length = 64, unique = true)
    private String captureIdempotencyKey;

    /** 关联原始预授权支付单（退款时指向预授权单） */
    private Long originPaymentId;

    @Column(length = 255)
    private String failReason;

    private LocalDateTime authorizedAt;
    private LocalDateTime capturedAt;
    private LocalDateTime releasedAt;
    private LocalDateTime refundedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
