package com.citylaundry.model;

import com.citylaundry.model.Enums.CompensationStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 赔付：逐衣物立案，不允许同订单多件衣物混在一起赔付 */
@Getter @Setter
@Entity @Table(name = "compensation_case")
public class CompensationCase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String caseNo;

    @Column(nullable = false)
    private Long itemId;

    @Column(nullable = false)
    private Long ticketId;

    /** 赔付金额（针对该单件衣物） */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 16)
    private String responsibility; // STORE / FACTORY / LOGISTICS / INSURANCE

    /** 是否走保险赔付 */
    @Column(nullable = false)
    private boolean insuranceClaim = false;

    @Column(length = 64)
    private String insurancePolicyNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private CompensationStatus status = CompensationStatus.PENDING;

    @Column(nullable = false)
    private Long createdBy;

    private Long approvedBy;

    @Column(length = 512)
    private String reason;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime paidAt;

    /** 是否进入门店质检会议复盘 */
    @Column(nullable = false)
    private boolean reviewedInQc = false;

    private Long qcMeetingId;
}
