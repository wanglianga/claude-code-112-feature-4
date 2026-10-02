package com.citylaundry.model;

import com.citylaundry.model.Enums.RecoveryStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 骑手回收任务：拒绝承接 / 客户退回选择时生成。
 * 重派幂等：同一异常+同一原因只允许存在一个未取消任务，重复重派返回原任务。
 */
@Getter @Setter
@Entity @Table(name = "recovery_task")
public class RecoveryTask {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String taskNo;

    @Column(nullable = false)
    private Long itemId;

    @Column(nullable = false)
    private Long orderId;

    /** 关联异常项（可空：整单退回也可生成） */
    private Long exceptionId;

    /** 触发原因：REJECT_ORDER / REJECT_REFUND / REDISPATCH ... */
    @Column(nullable = false, length = 32)
    private String reason;

    @Column(nullable = false)
    private Long riderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RecoveryStatus status = RecoveryStatus.PENDING;

    /** 回收地址快照 */
    @Column(nullable = false, length = 255)
    private String returnAddress;

    private LocalDateTime dispatchedAt;
    private LocalDateTime doneAt;

    /** 回收留影 URL */
    @Column(length = 512)
    private String handoverPhotoUrl;

    /** 幂等键：同一异常+原因复用 */
    @Column(nullable = false, length = 80)
    private String idempotencyKey;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
