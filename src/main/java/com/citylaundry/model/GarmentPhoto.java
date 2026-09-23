package com.citylaundry.model;

import com.citylaundry.model.Enums.PhotoStage;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 衣物照片：按件 + 按阶段留存 */
@Getter @Setter
@Entity @Table(name = "garment_photo")
public class GarmentPhoto {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long itemId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private PhotoStage stage;

    /** 照片地址（演示环境存 URL/占位路径） */
    @Column(nullable = false, length = 512)
    private String url;

    @Column(nullable = false)
    private Long uploadedBy;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
