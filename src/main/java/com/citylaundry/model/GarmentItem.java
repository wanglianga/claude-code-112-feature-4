package com.citylaundry.model;

import com.citylaundry.model.Enums.GarmentStatus;
import com.citylaundry.model.Enums.ProcessType;
import com.citylaundry.model.Enums.RiskLevel;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 单件衣物：独立编号、独立照片/工艺/交接/售后/赔付记录，
 * 同一订单内多件衣物互不混淆。
 */
@Getter @Setter
@Entity @Table(name = "garment_item")
public class GarmentItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 独立编号，如 G20260923-0001-01 */
    @Column(nullable = false, unique = true, length = 40)
    private String itemCode;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false, length = 64)
    private String type; // 衣物类型：西装/大衣/羽绒服/皮衣/工服...

    @Column(length = 64)
    private String brand;

    @Column(nullable = false, length = 64)
    private String material; // 材质：棉/羊毛/真丝/皮革/化纤...

    @Column(length = 64)
    private String color;

    @Column(length = 255)
    private String stains; // 污渍描述

    @Column(length = 255)
    private String existingDamage; // 已有破损

    @Column(length = 255)
    private String accessories; // 配饰：腰带/纽扣/毛领...

    /** 洗标是否存在 */
    @Column(nullable = false)
    private boolean careLabelPresent = true;

    /** 声明价值（元） */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal declaredValue = BigDecimal.ZERO;

    /** 是否奢侈品护理 */
    @Column(nullable = false)
    private boolean luxury = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RiskLevel riskLevel = RiskLevel.LOW;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private ProcessType processType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private GarmentStatus status = GarmentStatus.REGISTERED;

    /** 当前所在工厂站点 */
    private Long currentSiteId;

    /** 基础工艺价（分配工艺时落价） */
    @Column(precision = 12, scale = 2)
    private BigDecimal basePrice = BigDecimal.ZERO;

    /** 客户已确认的追加报价金额（未确认/撤回时清零） */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal agreedExtra = BigDecimal.ZERO;

    /** 客户已确认的追加报价版本 id（撤回后置空，历史版本仍保留） */
    private Long agreedQuoteVersionId;

    /** 衣物级预计交付时间（异常决策导致延期时按件更新；空则取订单预计时间） */
    private LocalDateTime expectedDeliveryAt;

    /** 骑手回收任务 id（客户拒绝承接时生成；按原服务继续/撤回时取消） */
    private Long reclaimId;

    /** 入厂质检结论 */
    @Column(length = 16)
    private String qcConclusion = "PENDING";

    /** 进入异常报价分支前的业务状态（REGISTERED/PHOTOGRAPHED/AT_FACTORY/PROCESS_ASSIGNED/IN_PROCESS），决策后据此恢复 */
    @Column(length = 32)
    private String resumeStatus;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
