package com.citylaundry.model;

import com.citylaundry.model.Enums.ManualReviewResult;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 人工复核案：客户对报价申请人工复核、或客户超时未决策时进入人工队列。
 * 客服复核可以维持/下调追加额、维持原价、仅基础处理、拒单；
 * 下调或变更方案时必须生成新版报价（旧版 SUPERSEDED，不覆盖）。
 */
@Getter @Setter
@Entity @Table(name = "manual_review_case")
public class ManualReviewCase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String reviewNo;

    @Column(nullable = false)
    private Long exceptionId;

    @Column(nullable = false)
    private Long itemId;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long customerId;

    /** 来源：CUSTOMER_REQUEST（客户申请）/ TIMEOUT（超时自动）/ CLOSEOUT_BLOCK（关单缺项） */
    @Column(nullable = false, length = 24)
    private String source = "CUSTOMER_REQUEST";

    @Column(length = 512)
    private String customerReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ManualReviewResult result = ManualReviewResult.PENDING;

    /** 复核人（客服） */
    private Long handlerId;

    /** 复核确认的追加金额（REDUCE_SURCHARGE 时使用） */
    @Column(precision = 12, scale = 2)
    private BigDecimal adjustedSurcharge;

    @Column(length = 512)
    private String conclusion;

    /** 复核后新生成的报价版本 id */
    private Long newQuoteRoundId;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime handledAt;
}
