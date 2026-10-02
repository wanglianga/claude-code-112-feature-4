package com.citylaundry.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 门店洗护排产：按 门店 × 日期 记录产能占用。
 * 客户确认追加报价才占用产能；拒绝/退回/撤回时释放对应占用。
 */
@Getter @Setter
@Entity @Table(name = "production_schedule",
        uniqueConstraints = @UniqueConstraint(columnNames = {"store_id", "schedule_date"}))
public class ProductionSchedule {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "schedule_date", nullable = false)
    private LocalDate scheduleDate;

    /** 已占用产能（件） */
    @Column(nullable = false)
    private int occupied = 0;

    /** 日产能上限冗余（建槽时快照） */
    @Column(nullable = false)
    private int capacity = 100;

    private LocalDateTime updatedAt = LocalDateTime.now();
}
