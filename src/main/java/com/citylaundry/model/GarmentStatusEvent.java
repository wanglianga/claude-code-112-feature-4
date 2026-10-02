package com.citylaundry.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 衣物级状态机流转事件：每次状态迁移留一条不可变事件，
 * 支撑「多次补拍、骑手与门店判断不一致、客户确认后撤回、支付回调失败」等场景的完整审计。
 */
@Getter @Setter
@Entity @Table(name = "garment_status_event")
public class GarmentStatusEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long itemId;

    @Column(nullable = false, length = 32)
    private String fromStatus;

    @Column(nullable = false, length = 32)
    private String toStatus;

    /** 触发原因/事件类型（如 ISSUE_RAISED / QUOTE_RESOLVED / RECLAIM_DONE / PAY_FAILED） */
    @Column(nullable = false, length = 48)
    private String event;

    @Column(length = 255)
    private String refType; // ISSUE / QUOTE / DECISION / REVIEW / PAYMENT / RECLAIM / QC

    private Long refId;

    @Column(nullable = false)
    private Long operatorId; // 0 = 系统

    @Column(length = 512)
    private String note;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
