package com.citylaundry.model;

import com.citylaundry.model.Enums.SettlementStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单结算结果：关单前校验通过后自动结算。
 * 幂等：一个订单只有一条结算记录；重复关单请求返回已有结果。
 */
@Getter @Setter
@Entity @Table(name = "settlement")
public class Settlement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private SettlementStatus status = SettlementStatus.PENDING;

    /** 最终应收（客户确认口径） */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal finalReceivable = BigDecimal.ZERO;

    /** 已请款/已付金额 */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal capturedAmount = BigDecimal.ZERO;

    /** 退款金额（客户拒绝并退回的衣物） */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal refundedAmount = BigDecimal.ZERO;

    /** 补偿金额（支付失败/责任补偿，可在人工队列处理） */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal compensatedAmount = BigDecimal.ZERO;

    @Column(length = 1024)
    private String note;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime settledAt;
}
