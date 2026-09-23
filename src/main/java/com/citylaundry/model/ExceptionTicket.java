package com.citylaundry.model;

import com.citylaundry.model.Enums.ExceptionType;
import com.citylaundry.model.Enums.TicketStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 异常工单：围绕单件衣物，顾客/收衣员/工厂/客服/配送/财务协同处理 */
@Getter @Setter
@Entity @Table(name = "exception_ticket")
public class ExceptionTicket {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String ticketNo;

    @Column(nullable = false)
    private Long itemId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ExceptionType type;

    @Column(nullable = false, length = 512)
    private String description;

    /** 改地址时的新地址 */
    @Column(length = 255)
    private String newAddress;

    /** 延期时的新预计交付时间 */
    private LocalDateTime newExpectedDeliveryAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TicketStatus status = TicketStatus.OPEN;

    @Column(nullable = false)
    private Long raisedBy;

    /** 客服处理人 */
    private Long handlerId;

    @Column(length = 512)
    private String resolution;

    private LocalDateTime resolvedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
