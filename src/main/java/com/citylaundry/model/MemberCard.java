package com.citylaundry.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter @Setter
@Entity @Table(name = "member_card")
public class MemberCard {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String cardNo;

    @Column(nullable = false)
    private Long customerId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    /** 预授权冻结金额（确认收衣时冻结，扣款时转实收或拒绝承接时释放） */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal frozenBalance = BigDecimal.ZERO;

    @Column(nullable = false, length = 16)
    private String level = "NORMAL"; // NORMAL / SILVER / GOLD
}
