package com.citylaundry.model;

import com.citylaundry.model.Enums.CustomerAction;
import com.citylaundry.model.Enums.Responsibility;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 客户反馈：对某版报价的确认 / 拒绝(原路退回) / 拒绝(按原服务继续) /
 * 申请人工复核 / 确认后撤回。每次反馈独立留痕，保留决策时间。
 */
@Getter @Setter
@Entity @Table(name = "customer_feedback")
public class CustomerFeedback {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long exceptionId;

    @Column(nullable = false)
    private Long quoteRoundId;

    @Column(nullable = false)
    private Long itemId;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private CustomerAction action;

    /** 客户选择的报价方案类型快照 */
    @Column(length = 24)
    private String optionType;

    @Column(length = 512)
    private String note;

    /** 反馈时客户主张的责任归属（可空，最终由关单/人工复核认定） */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Responsibility claimedResponsibility;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
