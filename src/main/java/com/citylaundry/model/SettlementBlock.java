package com.citylaundry.model;

import com.citylaundry.model.Enums.CloseCheckStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 关单校验结果（每次执行关单校验生成一条）：
 * 校验证据、客户确认、质检结论、包装/取回记录是否齐全；
 * 任一缺项 → BLOCKED，阻止自动结算并转入人工队列。
 */
@Getter @Setter
@Entity @Table(name = "settlement_block")
public class SettlementBlock {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long itemId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CloseCheckStatus result;

    /** 缺项明细（EVIDENCE / CUSTOMER_CONFIRM / QC_RESULT / PACKAGING / HANDOVER 等，逗号分隔） */
    @Column(length = 512)
    private String missingItems;

    @Column(length = 512)
    private String detail;

    /** 进入人工队列后的处理人 */
    private Long handledBy;

    @Column(length = 512)
    private String handleNote;

    @Column(nullable = false)
    private boolean resolved = false;

    private LocalDateTime resolvedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
