package com.citylaundry.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 会员卡流水：洗护扣费（负）/ 赔付入账（正） */
@Getter @Setter
@Entity @Table(name = "member_transaction")
public class MemberTransaction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long cardId;

    private Long orderId;

    /** 关联赔付案（可空） */
    private Long compensationId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 24)
    private String type; // PAYMENT / COMPENSATION / RECHARGE

    @Column(length = 255)
    private String note;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
