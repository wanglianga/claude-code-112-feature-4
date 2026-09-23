package com.citylaundry.model;

import com.citylaundry.model.Enums.AfterSalesStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 售后记录（每件衣物独立）：交付时生成，时限内可追责 */
@Getter @Setter
@Entity @Table(name = "after_sales_record")
public class AfterSalesRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long itemId;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private LocalDateTime deliveredAt;

    @Column(nullable = false)
    private LocalDateTime deadline;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AfterSalesStatus status = AfterSalesStatus.ACTIVE;

    /** 交付时合并的洗护/复拍/赔付摘要，方便后续追责 */
    @Column(length = 1024)
    private String handoverSummary;
}
