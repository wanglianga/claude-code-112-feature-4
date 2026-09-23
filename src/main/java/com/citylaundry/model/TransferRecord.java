package com.citylaundry.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 交接记录：工厂多站点流转 / 环节交接留痕 */
@Getter @Setter
@Entity @Table(name = "transfer_record")
public class TransferRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long itemId;

    /** 起点站点（null 表示从门店/外部入厂） */
    private Long fromSiteId;

    @Column(nullable = false)
    private Long toSiteId;

    @Column(nullable = false)
    private Long operatorId;

    @Column(length = 255)
    private String note;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
