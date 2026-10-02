package com.citylaundry.model;

import com.citylaundry.model.Enums.QcConclusion;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 门店质检记录（逐件）：入厂时门店/工厂对实物与订单信息进行质检。
 * 发现材质不符/高风险污渍/配件缺失/不可去除瑕疵时结论 ANOMALY 并挂异常项，阻塞排产；
 * 关单前进行最终复检，确认包装/取回记录齐全。多次复检全部留痕，不覆盖原结论。
 */
@Getter @Setter
@Entity @Table(name = "store_qc_record")
public class StoreQcRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long itemId;

    /** INTAKE（入厂质检）/ FINAL（关单前复检） */
    @Column(nullable = false, length = 16)
    private String phase;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private QcConclusion conclusion = QcConclusion.PENDING;

    /** 实物材质/品牌核对结果 */
    @Column(length = 512)
    private String materialCheck;

    /** 配件清点结果 */
    @Column(length = 512)
    private String accessoryCheck;

    /** 包装确认（关单前复检） */
    private boolean packagingConfirmed = false;

    /** 取回/交接记录是否齐全（关单前复检） */
    private boolean handoverConfirmed = false;

    @Column(length = 512)
    private String note;

    @Column(nullable = false)
    private Long inspectorId;

    private Long issueId;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
