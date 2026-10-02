package com.citylaundry.service;

import com.citylaundry.common.ApiException;
import com.citylaundry.model.*;
import com.citylaundry.model.Enums.*;
import com.citylaundry.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 客户对报价版本的决策及其履约联动。
 * 确认追加报价 → 占门店产能、允许排产入厂；
 * 拒绝 → 二选一：原路退回（骑手回收+释放预授权）或按原服务/基础处理继续；
 * 申请人工复核 → 进入人工队列；确认后排产前可撤回（旧报价版本作废重出）。
 * 所有动作均留客户反馈与时间线；资金、产能、回收、ETA 随选择同步变化。
 */
@Service
public class DecisionService {
    private final GarmentExceptionRepo exceptionRepo;
    private final QuoteRoundRepo roundRepo;
    private final QuoteOptionRepo optionRepo;
    private final CustomerFeedbackRepo feedbackRepo;
    private final ManualReviewCaseRepo reviewRepo;
    private final GarmentItemRepo itemRepo;
    private final LaundryOrderRepo orderRepo;
    private final GarmentStateMachine sm;
    private final ExceptionService exceptionService;
    private final CapacityService capacity;
    private final RecoveryService recovery;
    private final PaymentService payment;
    private final PricingService pricing;
    private final TimelineService timeline;

    public DecisionService(GarmentExceptionRepo exceptionRepo, QuoteRoundRepo roundRepo,
                           QuoteOptionRepo optionRepo, CustomerFeedbackRepo feedbackRepo,
                           ManualReviewCaseRepo reviewRepo, GarmentItemRepo itemRepo,
                           LaundryOrderRepo orderRepo, GarmentStateMachine sm,
                           ExceptionService exceptionService, CapacityService capacity,
                           RecoveryService recovery, PaymentService payment,
                           PricingService pricing, TimelineService timeline) {
        this.exceptionRepo = exceptionRepo;
        this.roundRepo = roundRepo;
        this.optionRepo = optionRepo;
        this.feedbackRepo = feedbackRepo;
        this.reviewRepo = reviewRepo;
        this.itemRepo = itemRepo;
        this.orderRepo = orderRepo;
        this.sm = sm;
        this.exceptionService = exceptionService;
        this.capacity = capacity;
        this.recovery = recovery;
        this.payment = payment;
        this.pricing = pricing;
        this.timeline = timeline;
    }

    /** 客户确认指定版本的追加报价（roundId 为空=当前生效版本） */
    @Transactional
    public CustomerFeedback confirm(UserAccount customer, Long exceptionId, Long roundId, String note) {
        GarmentException ex = exceptionService.get(exceptionId);
        GarmentItem item = ownedItem(customer, ex.getItemId());
        QuoteRound round = resolveRound(ex, roundId);
        if (round.getStatus() != QuoteRoundStatus.ISSUED) {
            throw ApiException.conflict("该报价版本不可确认（状态 " + round.getStatus() + "）");
        }
        QuoteOption surcharge = optionOf(round, QuoteOptionType.SURCHARGE);
        if (!surcharge.isSelectable()) {
            throw ApiException.conflict("规则下该异常不可追加报价，请选择其他方案：" + surcharge.getExplanation());
        }
        if (item.getStatus() != GarmentStatus.AWAIT_CUSTOMER) {
            throw ApiException.conflict("衣物当前状态不允许确认报价: " + item.getStatus());
        }

        CustomerFeedback fb = saveFeedback(ex, round, item, customer, CustomerAction.CONFIRM,
                QuoteOptionType.SURCHARGE.name(), note, Responsibility.STORE);
        round.setStatus(QuoteRoundStatus.CONFIRMED);
        roundRepo.save(round);
        ex.setStatus(ExceptionStatus.CONFIRMED_SURCHARGE);
        ex.setResolution("客户确认追加 ¥" + round.getSurchargeAmount() + "（第" + round.getVersion() + "版）");
        ex.setResolvedAt(LocalDateTime.now());
        exceptionRepo.save(ex);

        item.setFinalPrice(surcharge.getTotalPrice());
        // 材质不符且客户确认追加：记录实际洗护材质用于工艺判定；
        // 订单登记材质保持不变（报价基准 + 异常 claimedMaterial 快照），原判断不被覆盖。
        if (ex.getCategory() == ExceptionCategory.MATERIAL_MISMATCH
                && ex.getObservedMaterial() != null && !ex.getObservedMaterial().isBlank()) {
            item.setConfirmedMaterial(ex.getObservedMaterial());
        }
        sm.transit(item, GarmentStatus.QUOTE_CONFIRMED, "客户确认追加报价");
        itemRepo.save(item);

        // 占门店洗护产能（追加方案 +1 天缓冲），允许排产
        java.time.LocalDate date = capacity.occupy(item, surcharge.getEtaImpactHours() > 0 ? 1 : 0);

        // 支付联动：追加后订单总额高于初始预授权时，补充授权差额（幂等按异常+版本）
        LaundryOrder order = orderRepo.findById(ex.getOrderId()).orElseThrow();
        java.math.BigDecimal expectedTotal = pricing.negotiatedTotal(
                order, itemRepo.findByOrderIdOrderByIdAsc(order.getId()));
        PaymentOrder po = payment.ofOrder(order.getId());
        if (po != null) {
            java.math.BigDecimal extra = expectedTotal.subtract(po.getPreauthAmount());
            if (extra.signum() > 0) {
                payment.increasePreauth(order.getId(), extra,
                        "preauth-confirm-" + ex.getId() + "-v" + round.getVersion());
            }
        }

        // ETA：追加专项洗护顺延
        if (surcharge.getEtaImpactHours() > 0) {
            shiftEta(item.getOrderId(), surcharge.getEtaImpactHours());
        }
        timeline.add(ex.getOrderId(), item.getId(), "FEEDBACK",
                "客户【确认追加报价】¥" + surcharge.getTotalPrice() + "，排产 " + date
                        + "，允许进入洗护", customer, round.getQuoteNo());
        OrderFlowSupport.advanceIfNoHolds(orderRepo, itemRepo, sm, ex.getOrderId());
        return fb;
    }

    /**
     * 客户拒绝。mode=REFUND 原路退回并回收衣物；mode=CONTINUE 按原服务或仅基础处理继续。
     * continueOption: KEEP_ORIGINAL_PRICE / BASIC_TREATMENT_ONLY
     */
    @Transactional
    public CustomerFeedback reject(UserAccount customer, Long exceptionId, Long roundId,
                                   String mode, String continueOption, String note) {
        GarmentException ex = exceptionService.get(exceptionId);
        GarmentItem item = ownedItem(customer, ex.getItemId());
        QuoteRound round = resolveRound(ex, roundId);
        if (round.getStatus() != QuoteRoundStatus.ISSUED) {
            throw ApiException.conflict("该报价版本不可拒绝（状态 " + round.getStatus() + "）");
        }
        if (item.getStatus() != GarmentStatus.AWAIT_CUSTOMER) {
            throw ApiException.conflict("衣物当前状态不允许拒绝报价: " + item.getStatus());
        }

        boolean refund = !"CONTINUE".equalsIgnoreCase(mode);
        if (refund) {
            CustomerFeedback fb = saveFeedback(ex, round, item, customer, CustomerAction.REJECT_REFUND,
                    QuoteOptionType.REJECT_ORDER.name(), note, Responsibility.PENDING);
            round.setStatus(QuoteRoundStatus.REJECTED);
            roundRepo.save(round);
            ex.setStatus(ExceptionStatus.REJECTED);
            ex.setResolution("客户拒绝追加报价，选择原路退回并回收衣物");
            ex.setResolvedAt(LocalDateTime.now());
            exceptionRepo.save(ex);

            sm.transit(item, GarmentStatus.REJECTED, "客户拒绝→退回");
            itemRepo.save(item);
            // 资金联动：释放该件预授权（原价+追加授权，未请款）；如已请款则原路退款
            BigDecimal locked = round.getBasePrice().add(round.getSurchargeAmount());
            if (item.getCapturedAmount().signum() > 0) {
                payment.refund(ex.getOrderId(), item.getCapturedAmount(),
                        "refund-item-" + item.getId(), "客户拒绝追加，原路退回");
            } else {
                payment.releaseAmount(ex.getOrderId(), locked, "release-item-" + item.getId() + "-v"
                        + round.getVersion(), "客户拒绝追加，释放预授权");
            }
            // 履约联动：骑手回收（幂等重派）
            recovery.dispatch(item.getId(), ex.getId(), "REJECT_REFUND",
                    "ex" + ex.getId() + "-REJECT_REFUND");
            timeline.add(ex.getOrderId(), item.getId(), "FEEDBACK",
                    "客户【拒绝并原路退回】，预授权释放 ¥" + locked + "，骑手回收已派单",
                    customer, round.getQuoteNo());
            OrderFlowSupport.advanceIfNoHolds(orderRepo, itemRepo, sm, ex.getOrderId());
            return fb;
        }

        // 继续：维持原价 或 仅基础处理
        QuoteOptionType chosen = "BASIC_TREATMENT_ONLY".equalsIgnoreCase(continueOption)
                ? QuoteOptionType.BASIC_TREATMENT_ONLY : QuoteOptionType.KEEP_ORIGINAL_PRICE;
        QuoteOption opt = optionOf(round, chosen);
        if (!opt.isSelectable()) {
            throw ApiException.conflict("规则下该方案不可选：" + opt.getExplanation());
        }
        CustomerFeedback fb = saveFeedback(ex, round, item, customer, CustomerAction.REJECT_CONTINUE,
                chosen.name(), note, chosen == QuoteOptionType.BASIC_TREATMENT_ONLY
                        ? Responsibility.CUSTOMER : Responsibility.PENDING);
        round.setStatus(QuoteRoundStatus.CONFIRMED); // 客户按该方案达成一致
        roundRepo.save(round);

        if (chosen == QuoteOptionType.BASIC_TREATMENT_ONLY) {
            ex.setStatus(ExceptionStatus.BASIC_ONLY);
            ex.setResolution("客户选择仅基础处理 ¥" + opt.getTotalPrice());
            item.setFinalPrice(opt.getTotalPrice());
            item.setProcessType(ProcessType.BASIC);
            sm.transit(item, GarmentStatus.BASIC_ONLY, "客户选择仅基础处理");
        } else {
            ex.setStatus(ExceptionStatus.RESOLVED_KEEP_PRICE);
            ex.setResolution("客户选择按原服务维持原价 ¥" + opt.getTotalPrice());
            item.setFinalPrice(opt.getTotalPrice());
            sm.transit(item, GarmentStatus.CONFIRMED, "客户选择维持原价继续");
        }
        ex.setResolvedAt(LocalDateTime.now());
        exceptionRepo.save(ex);
        itemRepo.save(item);
        capacity.occupy(item, 0);
        timeline.add(ex.getOrderId(), item.getId(), "FEEDBACK",
                "客户【拒绝追加，按" + (chosen == QuoteOptionType.BASIC_TREATMENT_ONLY ? "仅基础处理" : "原服务")
                        + "继续】，成交价 ¥" + opt.getTotalPrice() + "，产能已保留",
                customer, round.getQuoteNo());
        OrderFlowSupport.advanceIfNoHolds(orderRepo, itemRepo, sm, ex.getOrderId());
        return fb;
    }

    /** 客户申请人工复核：报价挂起进入人工队列 */
    @Transactional
    public CustomerFeedback requestManualReview(UserAccount customer, Long exceptionId,
                                                Long roundId, String reason) {
        GarmentException ex = exceptionService.get(exceptionId);
        GarmentItem item = ownedItem(customer, ex.getItemId());
        QuoteRound round = resolveRound(ex, roundId);
        if (round.getStatus() != QuoteRoundStatus.ISSUED) {
            throw ApiException.conflict("该报价版本不可申请复核（状态 " + round.getStatus() + "）");
        }
        CustomerFeedback fb = saveFeedback(ex, round, item, customer, CustomerAction.MANUAL_REVIEW,
                null, reason, Responsibility.PENDING);
        round.setStatus(QuoteRoundStatus.DISPUTED);
        roundRepo.save(round);
        ex.setStatus(ExceptionStatus.MANUAL_REVIEW);
        exceptionRepo.save(ex);
        sm.transit(item, GarmentStatus.AWAIT_MANUAL_REVIEW, "客户申请人工复核");
        itemRepo.save(item);

        ManualReviewCase c = new ManualReviewCase();
        c.setExceptionId(ex.getId());
        c.setItemId(item.getId());
        c.setOrderId(ex.getOrderId());
        c.setCustomerId(customer.getId());
        c.setSource("CUSTOMER_REQUEST");
        c.setCustomerReason(reason);
        c.setReviewNo("TMP");
        c = reviewRepo.save(c);
        c.setReviewNo("M" + String.format("%08d", c.getId()));
        c = reviewRepo.saveAndFlush(c);

        timeline.add(ex.getOrderId(), item.getId(), "FEEDBACK",
                "客户【申请人工复核】：" + reason + "，进入人工队列 " + c.getReviewNo(),
                customer, c.getReviewNo());
        return fb;
    }

    /**
     * 客户确认后撤回（限排产入厂前、未请款）。
     * 释放产能 → 已确认版本 SUPERSEDED → 异常回到待决 → 重出一版报价。
     */
    @Transactional
    public CustomerFeedback withdraw(UserAccount customer, Long exceptionId, String note) {
        GarmentException ex = exceptionService.get(exceptionId);
        GarmentItem item = ownedItem(customer, ex.getItemId());
        if (item.getStatus() != GarmentStatus.QUOTE_CONFIRMED) {
            throw ApiException.conflict("仅已确认待排产的衣物可以撤回: " + item.getStatus());
        }
        if (item.getCapturedAmount().signum() > 0) {
            throw ApiException.conflict("该衣物已请款进入洗护，不能撤回（请走售后/赔付）");
        }
        QuoteRound round = roundRepo.findById(item.getCurrentQuoteRoundId())
                .orElse(roundRepo.findFirstByExceptionIdOrderByVersionDesc(ex.getId()).orElseThrow());
        CustomerFeedback fb = saveFeedback(ex, round, item, customer, CustomerAction.WITHDRAW,
                QuoteOptionType.SURCHARGE.name(), note, Responsibility.PENDING);

        capacity.release(item); // 释放已占产能
        round.setStatus(QuoteRoundStatus.SUPERSEDED);
        roundRepo.save(round);
        ex.setStatus(ExceptionStatus.WITHDRAWN);
        ex.setResolution("客户确认后撤回，第" + round.getVersion() + "版报价作废，重新出具新版");
        exceptionRepo.save(ex);

        sm.transit(item, GarmentStatus.EXCEPTION_PENDING, "客户撤回确认");
        item.setFinalPrice(null);
        itemRepo.save(item);
        exceptionService.issueQuote(ex.getId(), "CUSTOMER_WITHDRAW", null, null);

        timeline.add(ex.getOrderId(), item.getId(), "FEEDBACK",
                "客户【撤回确认】，释放产能，旧版报价 " + round.getQuoteNo() + " 作废，新版本已下发",
                customer, round.getQuoteNo());
        OrderFlowSupport.advanceIfNoHolds(orderRepo, itemRepo, sm, ex.getOrderId());
        return fb;
    }

    /** 客户决策超时 → 自动转人工（扫描调用） */
    @Transactional
    public ManualReviewCase timeoutToManual(GarmentException ex) {
        GarmentItem item = itemRepo.findById(ex.getItemId()).orElseThrow();
        if (item.getStatus() != GarmentStatus.AWAIT_CUSTOMER) return null;
        sm.transit(item, GarmentStatus.AWAIT_MANUAL_REVIEW, "客户超时未决策，自动转人工");
        itemRepo.save(item);
        ex.setStatus(ExceptionStatus.MANUAL_REVIEW);
        exceptionRepo.save(ex);
        ManualReviewCase c = new ManualReviewCase();
        c.setExceptionId(ex.getId());
        c.setItemId(item.getId());
        c.setOrderId(ex.getOrderId());
        c.setCustomerId(ex.getRaisedBy());
        c.setSource("TIMEOUT");
        c.setCustomerReason("客户在时限内未决策，系统自动转人工");
        c.setReviewNo("TMP");
        c = reviewRepo.save(c);
        c.setReviewNo("M" + String.format("%08d", c.getId()));
        c = reviewRepo.saveAndFlush(c);
        timeline.add(ex.getOrderId(), item.getId(), "FEEDBACK",
                "客户超时未决策（截止 " + ex.getDecideDeadline() + "），自动转人工队列 "
                        + c.getReviewNo(), 0L, "SYSTEM", c.getReviewNo());
        return c;
    }

    private QuoteRound resolveRound(GarmentException ex, Long roundId) {
        if (roundId != null) {
            QuoteRound r = roundRepo.findById(roundId)
                    .orElseThrow(() -> ApiException.notFound("报价版本不存在"));
            if (!r.getExceptionId().equals(ex.getId())) {
                throw ApiException.badRequest("报价版本与异常不匹配");
            }
            return r;
        }
        return roundRepo.findFirstByExceptionIdOrderByVersionDesc(ex.getId())
                .orElseThrow(() -> ApiException.conflict("该异常尚未出具报价"));
    }

    private QuoteOption optionOf(QuoteRound round, QuoteOptionType type) {
        return optionRepo.findByQuoteRoundIdOrderByIdAsc(round.getId()).stream()
                .filter(o -> o.getType() == type).findFirst()
                .orElseThrow(() -> ApiException.conflict("报价缺少候选方案 " + type));
    }

    private CustomerFeedback saveFeedback(GarmentException ex, QuoteRound round, GarmentItem item,
                                          UserAccount customer, CustomerAction action,
                                          String optionType, String note,
                                          Responsibility claimed) {
        CustomerFeedback fb = new CustomerFeedback();
        fb.setExceptionId(ex.getId());
        fb.setQuoteRoundId(round.getId());
        fb.setItemId(item.getId());
        fb.setOrderId(ex.getOrderId());
        fb.setCustomerId(customer.getId());
        fb.setAction(action);
        fb.setOptionType(optionType);
        fb.setNote(note);
        fb.setClaimedResponsibility(claimed);
        return feedbackRepo.save(fb);
    }

    private GarmentItem ownedItem(UserAccount customer, Long itemId) {
        GarmentItem item = itemRepo.findById(itemId).orElseThrow(() -> ApiException.notFound("衣物不存在"));
        LaundryOrder order = orderRepo.findById(item.getOrderId()).orElseThrow();
        if (!order.getCustomerId().equals(customer.getId())) {
            throw ApiException.forbidden("只能对自己的订单衣物做决策");
        }
        return item;
    }

    private void shiftEta(Long orderId, int hours) {
        LaundryOrder order = orderRepo.findById(orderId).orElseThrow();
        java.time.LocalDateTime base = order.getBaseExpectedDeliveryAt() != null
                ? order.getBaseExpectedDeliveryAt() : order.getExpectedDeliveryAt();
        order.setExpectedDeliveryAt(base.plusHours(hours));
        orderRepo.save(order);
    }
}
