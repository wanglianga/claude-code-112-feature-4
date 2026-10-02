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
    private String material; // 订单登记材质：棉/羊毛/真丝/皮革/化纤...（报价基准，永不覆盖）

    /** 客户确认材质不符追加后的【实际洗护材质】；仅影响工艺判定，订单登记材质保留 */
    @Column(length = 64)
    private String confirmedMaterial;

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

    /** 进入异常/支付挂起前的衣物状态：客户撤回后回到该状态，原判断不被覆盖 */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private GarmentStatus statusBeforeHold;

    /** 最终成交价（客户确认追加后的价格；为空时按交付账单规则计价） */
    @Column(precision = 12, scale = 2)
    private BigDecimal finalPrice;

    /** 当前生效报价轮次 id（历史版本保留在 quote_round 表，不覆盖） */
    private Long currentQuoteRoundId;

    /** 已排产日期（幂等占用/释放产能的依据，null=未排产） */
    private java.time.LocalDate productionDate;

    /** 该件已通过支付单请款金额（入厂洗护时幂等请款） */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal capturedAmount = BigDecimal.ZERO;

    /** 当前所在工厂站点 */
    private Long currentSiteId;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
