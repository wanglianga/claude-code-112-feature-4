package com.citylaundry.model;

import com.citylaundry.model.Enums.OrderStatus;
import com.citylaundry.model.Enums.OrderType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter @Setter
@Entity @Table(name = "laundry_order")
public class LaundryOrder {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String orderNo;

    @Column(nullable = false)
    private Long customerId;

    @Column(nullable = false)
    private Long storeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private OrderType type = OrderType.NORMAL;

    /** 门店自取（true）或配送上门（false） */
    @Column(nullable = false)
    private boolean selfPickup = false;

    @Column(nullable = false, length = 255)
    private String pickupAddress;

    @Column(nullable = false, length = 255)
    private String deliveryAddress;

    @Column(nullable = false)
    private Long memberCardId;

    @Column(nullable = false)
    private LocalDateTime expectedDeliveryAt;

    /** 下单时预计交付时间快照（客户不同选择导致 ETA 偏移时用于回算） */
    private LocalDateTime baseExpectedDeliveryAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderStatus status = OrderStatus.CREATED;

    @Column(precision = 12, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
