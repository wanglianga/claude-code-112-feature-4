package com.citylaundry.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** 工厂站点：支持多站点流转 */
@Getter @Setter
@Entity @Table(name = "factory_site")
public class FactorySite {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(length = 64)
    private String specialty; // 站点专长：通用/皮具/奢侈品
}
