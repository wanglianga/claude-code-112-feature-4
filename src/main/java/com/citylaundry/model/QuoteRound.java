package com.citylaundry.model;

import com.citylaundry.model.Enums.QuoteRoundStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 报价轮次（版本）：同一异常每次补拍/分歧/撤回都会生成新版本。
 * 历史轮次状态置 SUPERSEDED 但永久保留，绝不覆盖原判断与原报价。
 */
@Getter @Setter
@Entity @Table(name = "quote_round")
public class QuoteRound {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String quoteNo;

    @Column(nullable = false)
    private Long exceptionId;

    @Column(nullable = false)
    private Long itemId;

    @Column(nullable = false)
    private Long orderId;

    /** 第几版报价（从 1 开始） */
    @Column(nullable = false)
    private int version;

    /** 触发原因：INITIAL / RESHOOT / RIDER_STORE_DISAGREE / MANUAL_REVIEW / CUSTOMER_WITHDRAW */
    @Column(nullable = false, length = 32)
    private String reason;

    /** 原价（订单初始估价口径） */
    @Column(nullable = false, precision = 12, scale = 2)
    private java.math.BigDecimal basePrice;

    /** 规则推荐的追加金额（SURCHARGE 选项金额） */
    @Column(nullable = false, precision = 12, scale = 2)
    private java.math.BigDecimal surchargeAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private QuoteRoundStatus status = QuoteRoundStatus.DRAFT;

    /** 规则命中说明（审计：为何生成这些候选） */
    @Column(length = 1024)
    private String ruleTrace;

    @Column(nullable = false)
    private Long createdBy; // 0 = 系统规则引擎

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime issuedAt;
}
