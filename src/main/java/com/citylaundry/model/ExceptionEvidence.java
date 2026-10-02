package com.citylaundry.model;

import com.citylaundry.model.Enums.ExceptionSource;
import com.citylaundry.model.Enums.PhotoStage;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 异常证据：原始照片 + 拍摄时间 + 位置 + 拍摄人角色。
 * 支持对同一衣物多次补拍：每条证据独立留存，永不覆盖。
 */
@Getter @Setter
@Entity @Table(name = "exception_evidence")
public class ExceptionEvidence {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long exceptionId;

    @Column(nullable = false)
    private Long itemId;

    /** 证据轮次：骑手初判=1，门店补判=2，后续补拍递增 */
    @Column(nullable = false)
    private int roundNo = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ExceptionSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private PhotoStage stage = PhotoStage.EXCEPTION;

    @Column(nullable = false, length = 512)
    private String photoUrl;

    /** 拍摄时间（证据自带，独立于入库时间） */
    @Column(nullable = false)
    private LocalDateTime capturedAt;

    /** 拍摄位置（经纬度/文本） */
    @Column(nullable = false, length = 255)
    private String location;

    @Column(nullable = false)
    private Long uploadedBy;

    /** 补拍说明 / 与原判断的关系（如"门店复看：污渍面积扩大"） */
    @Column(length = 512)
    private String note;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
