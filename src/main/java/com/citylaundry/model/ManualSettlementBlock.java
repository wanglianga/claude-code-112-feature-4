package com.citylaundry.model;

import com.citylaundry.model.Enums.CheckItemType;
import com.citylaundry.model.Enums.CheckStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 关单校验明细：证据 / 客户确认 / 质检结论 / 包装 / 取回记录逐项检查。
 * 任一 MISSING → 阻止自动结算，订单转 MANUAL_REVIEW 并进入人工队列。
 */
@Getter @Setter
@Entity @Table(name = "manual_settlement_block")
public class ManualSettlementBlock {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    private Long itemId; // 订单级缺项可空

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private CheckItemType checkItem;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CheckStatus status = CheckStatus.MISSING;

    @Column(nullable = false, length = 512)
    private String detail;

    /** 处理该阻断项的客服 */
    private Long handledBy;

    @Column(length = 512)
    private String resolution;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime resolvedAt;
}
