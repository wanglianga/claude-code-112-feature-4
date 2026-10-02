package com.citylaundry.service;

import com.citylaundry.model.Enums.*;
import com.citylaundry.model.ExceptionAssessment;
import com.citylaundry.model.GarmentException;
import com.citylaundry.model.GarmentItem;
import com.citylaundry.model.QuoteOption;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 估价与规则报价引擎。
 * 规则输入：异常类别、严重度、骑手/门店双方研判（不一致时取更保守的一方）、
 * 订单登记材质与实际识别材质。输出固定 4 个候选：
 * 维持原价 / 追加报价 / 拒绝承接 / 仅基础处理，并标注每个候选是否可选及原因。
 */
@Service
public class PricingService {

    /** 按材质/工艺估算单件基础价格（下单估价口径，与交付账单一致） */
    public BigDecimal basePriceOf(GarmentItem item) {
        return priceOf(processOf(item));
    }

    public static ProcessType processOf(GarmentItem item) {
        if (item.isLuxury()) return ProcessType.LUXURY_CARE;
        String material = item.getMaterial() == null ? "" : item.getMaterial();
        if (containsAny(material, "皮革", "皮草", "PU")) return ProcessType.LEATHER_CARE;
        if (containsAny(material, "真丝", "羊绒", "羊毛")) return ProcessType.DRY_CLEAN;
        if (item.getStains() != null && !item.getStains().isBlank()) return ProcessType.DRY_CLEAN;
        return ProcessType.WET_CLEAN;
    }

    public static BigDecimal priceOf(ProcessType type) {
        if (type == null) return BigDecimal.ZERO;
        return switch (type) {
            case DRY_CLEAN -> new BigDecimal("30");
            case WET_CLEAN -> new BigDecimal("20");
            case LEATHER_CARE -> new BigDecimal("80");
            case LUXURY_CARE -> new BigDecimal("200");
            case BASIC -> new BigDecimal("10");
            case REJECTED -> BigDecimal.ZERO;
        };
    }

    /** 按实际识别材质重新估价（材质不符场景） */
    public BigDecimal priceByMaterial(String material, boolean luxury, String stains) {
        GarmentItem probe = new GarmentItem();
        probe.setLuxury(luxury);
        probe.setMaterial(material);
        probe.setStains(stains);
        return basePriceOf(probe);
    }

    /** 订单维度下单估价：各件基础价合计 + 团体折扣 + 配送费（预授权口径） */
    public BigDecimal estimateOrder(com.citylaundry.model.LaundryOrder order, List<GarmentItem> items) {
        BigDecimal subtotal = BigDecimal.ZERO;
        for (GarmentItem item : items) {
            subtotal = subtotal.add(basePriceOf(item));
        }
        if (order.getType() == OrderType.GROUP_WORKWEAR) {
            subtotal = subtotal.multiply(new BigDecimal("0.90"))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
        }
        if (!order.isSelfPickup()) subtotal = subtotal.add(new BigDecimal("5"));
        return subtotal.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** 客户决策后订单预计总额：成交价优先，退回件不计，加配送费 */
    public BigDecimal negotiatedTotal(com.citylaundry.model.LaundryOrder order, List<GarmentItem> items) {
        BigDecimal subtotal = BigDecimal.ZERO;
        for (GarmentItem item : items) {
            if (item.getStatus() == GarmentStatus.RETURNED
                    || item.getStatus() == GarmentStatus.RECOVERED) continue;
            subtotal = subtotal.add(item.getFinalPrice() != null ? item.getFinalPrice() : basePriceOf(item));
        }
        if (!order.isSelfPickup()) subtotal = subtotal.add(new BigDecimal("5"));
        return subtotal.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * 生成 4 个候选方案。
     * @param assessments 骑手/门店各自研判（规则采用风险更高、不可洗方意见）
     */
    public List<QuoteOption> buildOptions(GarmentException ex, GarmentItem item,
                                          List<ExceptionAssessment> assessments,
                                          BigDecimal basePrice) {
        boolean anyUnwashable = assessments.stream().anyMatch(a -> !a.isWashable());
        RiskLevel topRisk = assessments.stream()
                .map(a -> parseRisk(a.getRiskLevel()))
                .max(Enum::compareTo)
                .orElse(ex.getSeverity() == Severity.HIGH ? RiskLevel.HIGH : RiskLevel.MEDIUM);

        BigDecimal surcharge = computeSurcharge(ex, item, assessments, basePrice);
        List<QuoteOption> options = new ArrayList<>();

        // 1) 维持原价
        boolean keepSelectable = switch (ex.getCategory()) {
            // 高风险污渍/永久瑕疵且有一方判定不可洗 → 不能维持原价
            case HIGH_RISK_STAIN, PERMANENT_FLAW -> !(topRisk == RiskLevel.HIGH || anyUnwashable);
            // 材质升级到高风险材质（真丝/皮革等）→ 不能按原价承接
            case MATERIAL_MISMATCH -> !isHighRiskMaterial(ex.getObservedMaterial())
                    && ex.getSeverity() != Severity.HIGH;
            // 配件缺失可原价继续（仅记录风险）
            case ACCESSORY_MISSING, OTHER -> true;
        };
        options.add(option(QuoteOptionType.KEEP_ORIGINAL_PRICE, basePrice, BigDecimal.ZERO,
                keepSelectable, keepSelectable ? "按订单原价继续原服务"
                        : "规则禁止：高风险/不可洗情形不得维持原价", 0));

        // 2) 追加报价
        boolean surchargeSelectable = ex.getCategory() != ExceptionCategory.PERMANENT_FLAW
                && surcharge.compareTo(BigDecimal.ZERO) > 0;
        options.add(option(QuoteOptionType.SURCHARGE, basePrice.add(surcharge), surcharge,
                surchargeSelectable,
                surchargeSelectable ? "按实际材质/高风险污渍追加 ¥" + surcharge + " 后专项洗护"
                        : "规则禁止：瑕疵不可去除或无需追加费用",
                surchargeSelectable ? 24 : 0));

        // 3) 拒绝承接
        options.add(option(QuoteOptionType.REJECT_ORDER, BigDecimal.ZERO, BigDecimal.ZERO,
                true, "拒绝承接，预授权释放并安排骑手把衣物原路退回客户", 0));

        // 4) 仅基础处理
        boolean basicSelectable = ex.getCategory() != ExceptionCategory.ACCESSORY_MISSING
                || !anyUnwashable;
        BigDecimal basicPrice = priceOf(ProcessType.BASIC);
        options.add(option(QuoteOptionType.BASIC_TREATMENT_ONLY, basicPrice,
                basicPrice.subtract(basePrice),
                basicSelectable, "仅做基础清洁（不处理高风险污渍/瑕疵），收费 ¥" + basicPrice, 0));

        return options;
    }

    /** 规则计算追加金额（对外暴露，供报价轮次记录） */
    public BigDecimal ruleSurcharge(GarmentException ex, GarmentItem item,
                                    List<ExceptionAssessment> assessments, BigDecimal basePrice) {
        return computeSurcharge(ex, item, assessments, basePrice);
    }

    /** 规则计算追加金额 */
    private BigDecimal computeSurcharge(GarmentException ex, GarmentItem item,
                                        List<ExceptionAssessment> assessments,
                                        BigDecimal basePrice) {
        return switch (ex.getCategory()) {
            case MATERIAL_MISMATCH -> {
                if (ex.getObservedMaterial() == null) yield BigDecimal.ZERO;
                BigDecimal observed = priceByMaterial(ex.getObservedMaterial(),
                        item.isLuxury(), item.getStains());
                BigDecimal delta = observed.subtract(basePrice);
                yield delta.compareTo(BigDecimal.ZERO) > 0 ? delta : BigDecimal.ZERO;
            }
            case HIGH_RISK_STAIN -> {
                BigDecimal suggested = assessments.stream()
                        .map(ExceptionAssessment::getSuggestedSurcharge)
                        .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
                // 规则底限：高风险污渍专项去渍 ¥50
                if (suggested.compareTo(new BigDecimal("50")) < 0) suggested = new BigDecimal("50");
                yield suggested;
            }
            case ACCESSORY_MISSING -> new BigDecimal("30"); // 配饰拆除/手工保护
            case PERMANENT_FLAW, OTHER -> BigDecimal.ZERO;
        };
    }

    /** 规则命中说明（审计） */
    public String ruleTrace(GarmentException ex, List<ExceptionAssessment> assessments) {
        long riders = assessments.stream().filter(a -> a.getSource() == ExceptionSource.RIDER).count();
        long stores = assessments.stream().filter(a -> a.getSource() == ExceptionSource.STORE).count();
        boolean disagree = riders > 0 && stores > 0 && assessments.stream()
                .map(ExceptionAssessment::getRiskLevel).distinct().count() > 1;
        return "类别=" + ex.getCategory()
                + "; 严重度=" + ex.getSeverity()
                + "; 研判方=" + (riders > 0 ? "骑手" : "") + (stores > 0 ? "/门店" : "")
                + (disagree ? "; 骑手与门店判断不一致→采用更保守研判" : "")
                + "; 候选=维持原价/追加报价/拒绝承接/仅基础处理（按类别与风险置可选性）";
    }

    private QuoteOption option(QuoteOptionType type, BigDecimal total, BigDecimal delta,
                               boolean selectable, String explanation, int etaHours) {
        QuoteOption o = new QuoteOption();
        o.setType(type);
        o.setTotalPrice(total);
        o.setDelta(delta);
        o.setSelectable(selectable);
        o.setExplanation(explanation);
        o.setEtaImpactHours(etaHours);
        return o;
    }

    public static boolean isHighRiskMaterial(String material) {
        return material != null && containsAny(material, "真丝", "皮革", "皮草", "羊绒");
    }

    static boolean containsAny(String text, String... keys) {
        for (String k : keys) if (text.contains(k)) return true;
        return false;
    }

    private RiskLevel parseRisk(String s) {
        try {
            return RiskLevel.valueOf(s);
        } catch (Exception e) {
            return RiskLevel.MEDIUM;
        }
    }
}
