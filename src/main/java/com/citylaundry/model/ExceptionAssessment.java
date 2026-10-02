package com.citylaundry.model;

import com.citylaundry.model.Enums.ExceptionSource;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 异常研判：骑手与门店对同一异常可分别给出判断。
 * 双方判断不一致时各自保留一条研判记录（不覆盖原判断），
 * 规则报价综合全部研判，人工复核也以此为依据。
 */
@Getter @Setter
@Entity @Table(name = "exception_assessment")
public class ExceptionAssessment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long exceptionId;

    @Column(nullable = false)
    private Long itemId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ExceptionSource source;

    @Column(nullable = false)
    private Long assessorId;

    /** 判定的实际材质（材质不符场景） */
    @Column(length = 64)
    private String judgedMaterial;

    /** 风险研判：LOW/MEDIUM/HIGH */
    @Column(nullable = false, length = 16)
    private String riskLevel = "MEDIUM";

    /** 是否认为可继续洗护 */
    @Column(nullable = false)
    private boolean washable = true;

    /** 建议处理方式 */
    @Column(nullable = false, length = 512)
    private String recommendation;

    /** 建议追加费用（可为 0 = 维持原价） */
    @Column(nullable = false, precision = 12, scale = 2)
    private java.math.BigDecimal suggestedSurcharge = java.math.BigDecimal.ZERO;

    @Column(nullable = false)
    private LocalDateTime assessedAt = LocalDateTime.now();
}
