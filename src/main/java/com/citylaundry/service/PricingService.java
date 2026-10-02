package com.citylaundry.service;

import com.citylaundry.model.Enums.ProcessType;
import com.citylaundry.model.GarmentItem;

import java.math.BigDecimal;

/** 定价规则：工艺价与入厂前估价（用于下单估价与预授权金额） */
public final class PricingService {
    private PricingService() {}

    public static final BigDecimal DRY_CLEAN = new BigDecimal("30");
    public static final BigDecimal WET_CLEAN = new BigDecimal("20");
    public static final BigDecimal LEATHER_CARE = new BigDecimal("80");
    public static final BigDecimal LUXURY_CARE = new BigDecimal("200");
    public static final BigDecimal DELIVERY_FEE = new BigDecimal("5");

    public static BigDecimal priceOf(ProcessType type) {
        if (type == null) return BigDecimal.ZERO;
        return switch (type) {
            case DRY_CLEAN -> DRY_CLEAN;
            case WET_CLEAN -> WET_CLEAN;
            case LEATHER_CARE -> LEATHER_CARE;
            case LUXURY_CARE -> LUXURY_CARE;
            case REJECTED -> BigDecimal.ZERO;
        };
    }

    /** 入厂前按材质/奢侈品/污渍预估工艺（与 FactoryService.autoJudge 规则一致） */
    public static ProcessType estimateProcess(GarmentItem item) {
        if (item.isLuxury()) return ProcessType.LUXURY_CARE;
        String material = item.getMaterial() == null ? "" : item.getMaterial();
        if (OrderService.containsAny(material, "皮革", "皮草", "PU")) return ProcessType.LEATHER_CARE;
        if (OrderService.containsAny(material, "真丝", "羊绒", "羊毛")) return ProcessType.DRY_CLEAN;
        if (item.getStains() != null && !item.getStains().isBlank()) return ProcessType.DRY_CLEAN;
        return ProcessType.WET_CLEAN;
    }

    public static BigDecimal estimatePrice(GarmentItem item) {
        return priceOf(estimateProcess(item));
    }
}
