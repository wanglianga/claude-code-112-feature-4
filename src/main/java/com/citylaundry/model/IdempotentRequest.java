package com.citylaundry.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 通用幂等请求记录：退款、补偿、重派、洗护工序等非支付动作以业务键去重，
 * 避免重复退款 / 重复出库。唯一键由「动作 + 业务键」组成。
 */
@Getter @Setter
@Entity @Table(name = "idempotent_request",
        uniqueConstraints = @UniqueConstraint(name = "uk_idem_action_key", columnNames = {"action", "idem_key"}))
public class IdempotentRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 动作：REFUND / COMPENSATION / RECLAIM / WASH / SETTLE 等 */
    @Column(nullable = false, length = 32)
    private String action;

    @Column(nullable = false, length = 64, name = "idem_key")
    private String idempotencyKey;

    /** 关联资源 id（如 paymentId / itemId / caseId），便于检索 */
    private Long resourceId;

    @Column(length = 64)
    private String resultRef;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
