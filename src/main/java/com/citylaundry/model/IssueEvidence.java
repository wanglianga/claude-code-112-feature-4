package com.citylaundry.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 异常证据：原始照片 + 拍摄时间 + 拍摄位置 + 处理建议。
 * 同一异常项支持多次补拍（骑手补拍、门店复检补拍），全部留存不得覆盖；
 * 骑手与门店判断不一致时，各自证据独立挂载、各带发现环节与拍摄时间。
 */
@Getter @Setter
@Entity @Table(name = "issue_evidence")
public class IssueEvidence {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long issueId;

    @Column(nullable = false)
    private Long itemId;

    /** 照片地址（原始证据，不允许删除/改写） */
    @Column(nullable = false, length = 512)
    private String photoUrl;

    /** 拍摄时间（由上传方携带，服务端缺省取当前时间） */
    @Column(nullable = false)
    private LocalDateTime takenAt = LocalDateTime.now();

    /** 拍摄位置（地址或经纬度） */
    @Column(length = 255)
    private String location;

    /** 补拍序号：同一异常项第几次补拍（1=首次取证） */
    @Column(nullable = false)
    private int shotRound = 1;

    /** 取证环节：PICKUP / STORE_QC / REVIEW（人工复核补拍） */
    @Column(nullable = false, length = 16)
    private String sourceStage;

    @Column(nullable = false)
    private Long uploadedBy;

    /** 与既有判断不一致时的分歧说明（骑手/门店判断不一致留痕） */
    @Column(length = 512)
    private String disagreementNote;

    @Column(length = 512)
    private String suggestion;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
