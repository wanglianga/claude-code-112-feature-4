package com.citylaundry.model;

import com.citylaundry.model.Enums.ReclaimStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 骑手回收/重派任务：客户选择「拒绝承接→原路退回」时生成，
 * 同时门店释放该件占用的产能；客户改变主意（撤回/按原服务继续）则任务取消。
 * 回收必须留影（RECLAIM 照片），完成后衣物进入退回/退款流程。
 */
@Getter @Setter
@Entity @Table(name = "rider_reclaim")
public class RiderReclaim {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String reclaimNo;

    @Column(nullable = false)
    private Long itemId;

    @Column(nullable = false)
    private Long orderId;

    /** 取件地址（客户退回地址） */
    @Column(nullable = false, length = 255)
    private String reclaimAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReclaimStatus status = ReclaimStatus.PENDING;

    /** 被重派的骑手（空=待接单） */
    private Long riderId;

    @Column(length = 512)
    private String note;

    /** 回收留影 URL */
    @Column(length = 512)
    private String handoverPhotoUrl;

    private LocalDateTime assignedAt;
    private LocalDateTime reclaimedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
