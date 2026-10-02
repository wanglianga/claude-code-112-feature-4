package com.citylaundry.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 骑手取件记录：取件时间、位置（经纬度/文本）、骑手、客户签字。
 * 取件发生时建立取洗档案。
 */
@Getter @Setter
@Entity @Table(name = "pickup_record")
public class PickupRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long riderId;

    @Column(nullable = false)
    private LocalDateTime pickedAt = LocalDateTime.now();

    /** 取件位置（经纬度或文本地址快照） */
    @Column(nullable = false, length = 255)
    private String location;

    /** 客户签字/确认码（演示留痕） */
    @Column(length = 128)
    private String customerSign;

    /** 取件备注（骑手记录包装/件数） */
    @Column(length = 512)
    private String note;

    /** 送达门店时间 */
    private LocalDateTime deliveredToStoreAt;

    private LocalDateTime createdAt = LocalDateTime.now();
}
