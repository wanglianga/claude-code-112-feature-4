package com.citylaundry.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** 门店：高风险衣物会影响门店接单策略 */
@Getter @Setter
@Entity @Table(name = "store")
public class Store {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String name;

    /** 是否接收高风险衣物 */
    @Column(nullable = false)
    private boolean acceptHighRisk = true;

    /** 每日高风险衣物接单上限 */
    @Column(nullable = false)
    private int maxHighRiskPerDay = 10;
}
