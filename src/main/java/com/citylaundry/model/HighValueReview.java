package com.citylaundry.model;

import com.citylaundry.model.Enums.ReviewStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 高价值衣物专人复核：交付前必须完成二次拍照 + 包装确认 */
@Getter @Setter
@Entity @Table(name = "high_value_review")
public class HighValueReview {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long itemId;

    @Column(nullable = false)
    private Long reviewerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReviewStatus status = ReviewStatus.PENDING;

    @Column(length = 512)
    private String note;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime completedAt;
}
