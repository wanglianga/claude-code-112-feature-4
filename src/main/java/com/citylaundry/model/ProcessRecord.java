package com.citylaundry.model;

import com.citylaundry.model.Enums.ProcessType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 洗护工艺记录（每件衣物一条主工艺记录） */
@Getter @Setter
@Entity @Table(name = "process_record")
public class ProcessRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long itemId;

    @Column(nullable = false)
    private Long siteId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ProcessType processType;

    @Column(nullable = false)
    private Long operatorId;

    @Column(length = 512)
    private String note;

    @Column(nullable = false)
    private LocalDateTime startedAt = LocalDateTime.now();

    private LocalDateTime finishedAt;
}
