package com.citylaundry.model;

import com.citylaundry.model.Enums.Responsibility;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 门店收件质检记录：门店对骑手送达的逐件衣物做收件质检。
 * 材质/配件与订单不符、高风险污渍、不可去除瑕疵 → 建异常项，不能直接加价或洗护。
 */
@Getter @Setter
@Entity @Table(name = "store_qc_record")
public class StoreQcRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long itemId;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long inspectorId;

    @Column(nullable = false)
    private LocalDateTime inspectedAt = LocalDateTime.now();

    /** 门店实际识别材质（与订单材质可能不符） */
    @Column(nullable = false, length = 64)
    private String observedMaterial;

    /** 配件核对结果（描述实收配件） */
    @Column(length = 255)
    private String observedAccessories;

    /** 质检结论：PASS / 发现异常（异常另挂 garment_exception） */
    @Column(nullable = false, length = 512)
    private String conclusion;

    @Column(nullable = false)
    private boolean passed = true;

    /** 初步责任归属（最终责任以档案关单为准） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Responsibility responsibility = Responsibility.PENDING;
}
