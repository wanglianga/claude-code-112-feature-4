package com.citylaundry.model;

import com.citylaundry.model.Enums.CustomerAction;
import com.citylaundry.model.Enums.DecisionStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 客户反馈/决策：客户对每版报价可确认、拒绝（退回或按原服务继续）、申请人工复核、
 * 确认后撤回。所有决策动作逐次留痕；被新版本或撤回取代的旧决策置 SUPERSEDED 不删除。
 */
@Getter @Setter
@Entity @Table(name = "customer_decision")
public class CustomerDecision {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long issueId;

    @Column(nullable = false)
    private Long itemId;

    /** 决策针对的报价版本 */
    @Column(nullable = false)
    private Long quoteVersionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private CustomerAction action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private DecisionStatus status = DecisionStatus.PENDING;

    @Column(length = 512)
    private String customerNote;

    /** 决策时该件预计交付时间（按件同步影响 ETA，留痕） */
    private LocalDateTime expectedDeliveryAt;

    @Column(nullable = false)
    private Long decidedBy;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
