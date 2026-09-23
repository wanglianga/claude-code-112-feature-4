package com.citylaundry.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 门店质检会议：赔付复盘进入会议 */
@Getter @Setter
@Entity @Table(name = "qc_meeting")
public class QcMeeting {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String title;

    @Column(nullable = false)
    private Long storeId;

    @Column(nullable = false)
    private LocalDateTime heldAt;

    /** 复盘结论 */
    @Column(length = 1024)
    private String conclusion;
}
