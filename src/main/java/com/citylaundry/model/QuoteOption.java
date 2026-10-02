package com.citylaundry.model;

import com.citylaundry.model.Enums.QuoteOptionType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 候选报价方案：每个轮次固定生成 4 个候选——
 * 维持原价 / 追加报价 / 拒绝承接 / 仅基础处理。
 * 是否可选由规则引擎按异常类别/严重度/风险计算（如高风险污渍默认不可维持原价）。
 */
@Getter @Setter
@Entity @Table(name = "quote_option")
public class QuoteOption {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long quoteRoundId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private QuoteOptionType type;

    /** 该方案下客户应付总额 */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalPrice;

    /** 相对原价差额（追加为正，退回为 0） */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal delta;

    @Column(nullable = false)
    private boolean selectable = true;

    @Column(nullable = false, length = 512)
    private String explanation;

    /** 该方案对预计交付时间的影响（小时；0=不变） */
    @Column(nullable = false)
    private int etaImpactHours = 0;
}
