package com.citylaundry.model;

import com.citylaundry.model.Enums.QuoteOptionType;
import com.citylaundry.model.Enums.QuoteStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 报价版本：每次异常取证/补拍/人工裁决后生成一个新版本。
 * 一个版本内包含系统按规则生成的候选方案（维持原价 / 追加报价 / 拒绝承接 / 仅基础处理）。
 *
 * <p>新版本生效时旧版本置 SUPERSEDED 而非删除 —— 保留每次报价版本和证据，不得覆盖原判断。
 */
@Getter @Setter
@Entity @Table(name = "quote_version")
public class QuoteVersion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String quoteNo;

    @Column(nullable = false)
    private Long issueId;

    @Column(nullable = false)
    private Long itemId;

    /** 版本号，从 1 递增（补拍/复核裁决产生新版本） */
    @Column(nullable = false)
    private int versionNo;

    /** 触发原因：INITIAL（首次规则报价）/ RESHOT（补拍后）/ DISAGREEMENT（骑手门店不一致）/ MANUAL_REVIEW（人工裁决） */
    @Column(nullable = false, length = 24)
    private String reason;

    /** 原服务费（维持原价方案金额） */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal originalPrice;

    /** 追加金额（EXTRA_CHARGE 方案在原价基础上追加的金额） */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal extraAmount = BigDecimal.ZERO;

    /** 系统推荐的候选方案 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private QuoteOptionType recommendedOption;

    /** 本版本实际提供的候选方案（逗号分隔，如 KEEP_ORIGINAL,EXTRA_CHARGE,REJECT_ORDER,BASIC_ONLY） */
    @Column(nullable = false, length = 128)
    private String options;

    /** 规则引擎计算说明（可审计：为何出这些候选） */
    @Column(length = 1024)
    private String ruleNote;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private QuoteStatus status = QuoteStatus.DRAFT;

    @Column(nullable = false)
    private Long createdBy; // 0 = 系统规则引擎

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
