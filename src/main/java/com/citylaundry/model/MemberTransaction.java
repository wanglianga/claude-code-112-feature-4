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
    private String type; // PAYMENT / COMPENSATION / RECHARGE / PRE_AUTH_FREEZE / PRE_AUTH_RELEASE / REFUND

    /** 关联支付单（可空） */
    private Long paymentId;

    /** 幂等键：同一业务键重复请求只入账一次（唯一约束兜底） */
    @Column(length = 64, unique = true)
    private String idempotencyKey;

    @Column(length = 255)
    private String note;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
