package com.citylaundry.model;

import com.citylaundry.model.Enums.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 衣物异常项：骑手或门店发现材质与订单不符、高风险污渍、配件缺失、
 * 无法去除瑕疵时创建。禁止直接加价或继续洗护——必须附证据、研判、
 * 按规则生成候选报价，由客户在时限内决策。
 * 同一衣物可多次补拍 / 骑手与门店判断不一致 → 同一异常保留各自研判，不覆盖原判断。
 */
@Getter @Setter
@Entity @Table(name = "garment_exception")
public class GarmentException {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String exceptionNo;

    @Column(nullable = false)
    private Long itemId;

    @Column(nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ExceptionCategory category;

    /** 发现来源：骑手 / 门店质检 / 工厂 / 客户 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ExceptionSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Severity severity = Severity.MEDIUM;

    @Column(nullable = false, length = 512)
    private String description;

    /** 发现时订单登记材质（异常快照，留作材质不符比对） */
    @Column(length = 64)
    private String claimedMaterial;

    /** 发现方实际识别材质 */
    @Column(length = 64)
    private String observedMaterial;

    /** 处理建议（发现方填写） */
    @Column(length = 512)
    private String suggestedAction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ExceptionStatus status = ExceptionStatus.OPEN;

    @Column(nullable = false)
    private Long raisedBy;

    /** 客户决策截止时间（超时自动转人工） */
    @Column(nullable = false)
    private LocalDateTime decideDeadline;

    /** 最终责任归属 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Responsibility responsibility = Responsibility.PENDING;

    /** 关闭原因/最终结论 */
    @Column(length = 512)
    private String resolution;

    private LocalDateTime resolvedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** 版本号：每次补拍/分歧/撤回重报 +1 */
    @Column(nullable = false)
    private int version = 1;
}
