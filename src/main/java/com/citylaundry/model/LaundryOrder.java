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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderStatus status = OrderStatus.CREATED;

    @Column(precision = 12, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    /** 下单时基础估价（服务项+配送费） */
    @Column(precision = 12, scale = 2)
    private BigDecimal estimatedAmount = BigDecimal.ZERO;

    /** 已确认的追加报价合计 */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal extraAmount = BigDecimal.ZERO;

    /** 预授权支付单 id（顾客确认收衣时创建） */
    private Long preAuthPaymentId;

    /** 关单校验是否阻断自动结算 */
    @Column(nullable = false)
    private boolean settlementBlocked = false;

    /** 关单阻断原因（证据/确认/质检/包装取回缺项） */
    @Column(length = 512)
    private String settlementBlockReason;

    /** 结算完成时间 */
    private LocalDateTime settledAt;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
