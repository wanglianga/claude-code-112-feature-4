package com.citylaundry.model;

import com.citylaundry.model.Enums.AnomalyStatus;
import com.citylaundry.model.Enums.ExceptionType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 异常项：骑手取件或门店质检发现「材质与订单不符 / 高风险污渍 / 配件缺失 /
 * 无法去除的瑕疵」时，不能直接加价或继续洗护，必须先建异常项并附原始证据，
 * 系统按规则生成多候选报价版本，由客户在时限内决策。
 *
 * <p>同一衣物可被多次补拍、骑手与门店判断可能不一致：每次均生成新版本报价，
 * 原判断（异常项描述、证据、报价版本）永久保留、不得覆盖。
 */
@Getter @Setter
@Entity @Table(name = "anomaly_issue")
public class AnomalyIssue {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String issueNo;

    @Column(nullable = false)
    private Long itemId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ExceptionType type;

    @Column(nullable = false, length = 512)
    private String description;

    /** 风险程度说明（HIGH_RISK_STAIN 等高风险项用于规则与产能判断） */
    @Column(nullable = false, length = 16)
    private String severity = "MEDIUM"; // LOW / MEDIUM / HIGH

    /** 发现环节：PICKUP（骑手取件）/ STORE_QC（门店质检） */
    @Column(nullable = false, length = 16)
    private String foundStage;

    @Column(nullable = false)
    private Long raisedBy; // 0 = 系统

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AnomalyStatus status = AnomalyStatus.OPEN;

    /** 当前生效报价版本（历史版本 SUPERSEDED 保留） */
    private Long activeQuoteVersionId;

    /** 人工复核单（客户申请复核时关联） */
    private Long manualReviewId;

    /** 处理建议（发现人填写，供规则引擎与客户参考） */
    @Column(length = 512)
    private String suggestion;

    /** 材质不符时登记的实物材质（与订单材质对比，作为差价报价依据，不覆盖订单字段） */
    @Column(length = 64)
    private String actualMaterial;

    /** 实物品牌（可空） */
    @Column(length = 64)
    private String actualBrand;

    /** 客户决策截止时间（超时报价 EXPIRED，转人工队列） */
    @Column(nullable = false)
    private LocalDateTime decisionDeadline;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime closedAt;
}
