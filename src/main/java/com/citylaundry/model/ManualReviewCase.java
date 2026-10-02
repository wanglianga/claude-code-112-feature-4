package com.citylaundry.model;

import com.citylaundry.model.Enums.ReviewOutcome;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 人工复核单：客户对系统/门店报价有异议时申请，进入客服（可含门店经理）人工队列。
 * 客服裁决：维持原报价(UPHOLD_QUOTE) / 调整报价(ADJUST_QUOTE，重新出一版) /
 * 仅基础处理(BASIC_ONLY) / 拒绝承接(REJECT_ORDER)。裁决出新版报价后回到客户决策。
 */
@Getter @Setter
@Entity @Table(name = "manual_review_case")
public class ManualReviewCase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String reviewNo;

    @Column(nullable = false)
    private Long issueId;

    @Column(nullable = false)
    private Long itemId;

    @Column(nullable = false)
    private Long requestedBy;

    @Column(length = 512)
    private String requestReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ReviewOutcome outcome = ReviewOutcome.PENDING;

    /** 客服裁决说明 */
    @Column(length = 1024)
    private String verdictNote;

    /** ADJUST_QUOTE 时的新追加金额 */
    private java.math.BigDecimal adjustedExtra;

    private Long handledBy;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime handledAt;
}
