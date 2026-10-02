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
 * 人工复核队列处理：客户申请或超时转入。
 * 复核结论可维持追加、下调追加（必须生成新版报价，旧版保留）、
 * 维持原价、仅基础处理、拒绝承接。复核动作不覆盖任何历史判断。
 */
@Service
public class ManualReviewService {
    private final ManualReviewCaseRepo reviewRepo;
    private final GarmentExceptionRepo exceptionRepo;
    private final QuoteRoundRepo roundRepo;
    private final QuoteOptionRepo optionRepo;
    private final GarmentItemRepo itemRepo;
    private final CapacityService capacity;
    private final RecoveryService recovery;
    private final PaymentService payment;
    private final ExceptionService exceptionService;
    private final PricingService pricing;
    private final GarmentStateMachine sm;
    private final LaundryOrderRepo orderRepo;
    private final TimelineService timeline;

    public ManualReviewService(ManualReviewCaseRepo reviewRepo, GarmentExceptionRepo exceptionRepo,
                               QuoteRoundRepo roundRepo, QuoteOptionRepo optionRepo,
                               GarmentItemRepo itemRepo, CapacityService capacity,
                               RecoveryService recovery, PaymentService payment,
                               ExceptionService exceptionService, PricingService pricing,
                               GarmentStateMachine sm, LaundryOrderRepo orderRepo,
                               TimelineService timeline) {
        this.reviewRepo = reviewRepo;
        this.exceptionRepo = exceptionRepo;
        this.roundRepo = roundRepo;
        this.optionRepo = optionRepo;
        this.itemRepo = itemRepo;
        this.capacity = capacity;
        this.recovery = recovery;
        this.payment = payment;
        this.exceptionService = exceptionService;
        this.pricing = pricing;
        this.sm = sm;
        this.orderRepo = orderRepo;
        this.timeline = timeline;
    }

    @Transactional(readOnly = true)
    public List<ManualReviewCase> pending() {
        return reviewRepo.findByResult(ManualReviewResult.PENDING);
    }

    /**
     * 客服处理人工复核。
     * @param result UPHOLD_SURCHARGE / REDUCE_SURCHARGE / KEEP_ORIGINAL_PRICE / BASIC_ONLY / REJECT_ORDER
     * @param adjustedSurcharge 仅 REDUCE_SURCHARGE 必填
     */
    @Transactional
    public ManualReviewCase handle(UserAccount cs, Long reviewId, ManualReviewResult result,
                                   BigDecimal adjustedSurcharge, String conclusion) {
        ManualReviewCase c = reviewRepo.findById(reviewId)
                .orElseThrow(() -> ApiException.notFound("人工复核案不存在"));
        if (c.getResult() != ManualReviewResult.PENDING) {
            throw ApiException.conflict("复核案已处理: " + c.getResult());
        }
        if (conclusion == null || conclusion.isBlank()) {
            throw ApiException.badRequest("复核必须填写结论 conclusion");
        }
        GarmentException ex = exceptionRepo.findById(c.getExceptionId())
                .orElseThrow(() -> ApiException.notFound("异常项不存在"));
        GarmentItem item = itemRepo.findById(c.getItemId()).orElseThrow();
        QuoteRound disputed = roundRepo.findFirstByExceptionIdOrderByVersionDesc(ex.getId()).orElseThrow();

        c.setHandlerId(cs.getId());
        c.setResult(result);
        c.setConclusion(conclusion);
        c.setHandledAt(LocalDateTime.now());

        switch (result) {
            case UPHOLD_SURCHARGE -> {
                // 维持原追加额：生成新版本（原因 MANUAL_REVIEW），金额沿用争议版本
                disputed.setStatus(QuoteRoundStatus.SUPERSEDED);
                roundRepo.save(disputed);
                QuoteRound nr = exceptionService.issueQuote(ex.getId(), "MANUAL_REVIEW",
                        disputed.getSurchargeAmount(), cs);
                c.setAdjustedSurcharge(disputed.getSurchargeAmount());
                c.setNewQuoteRoundId(nr.getId());
            }
            case REDUCE_SURCHARGE -> {
                if (adjustedSurcharge == null || adjustedSurcharge.signum() < 0) {
                    throw ApiException.badRequest("下调追加必须填写 adjustedSurcharge（≥0）");
                }
                if (adjustedSurcharge.compareTo(disputed.getSurchargeAmount()) >= 0) {
                    throw ApiException.badRequest("下调金额必须小于原追加 ¥" + disputed.getSurchargeAmount());
                }
                disputed.setStatus(QuoteRoundStatus.SUPERSEDED);
                roundRepo.save(disputed);
                QuoteRound nr = exceptionService.issueQuote(ex.getId(), "MANUAL_REVIEW",
                        adjustedSurcharge, cs);
                c.setAdjustedSurcharge(adjustedSurcharge);
                c.setNewQuoteRoundId(nr.getId());
            }
            case KEEP_ORIGINAL_PRICE -> resolveKeepPrice(cs, c, ex, item, disputed, conclusion);
            case BASIC_ONLY -> resolveBasicOnly(cs, c, ex, item, disputed, conclusion);
            case REJECT_ORDER -> resolveReject(cs, c, ex, item, disputed, conclusion);
            default -> throw ApiException.badRequest("未知复核结论");
        }
        reviewRepo.save(c);
        timeline.add(ex.getOrderId(), item.getId(), "MANUAL_REVIEW",
                "人工复核结论 " + result + (c.getAdjustedSurcharge() != null
                        ? "，追加核定 ¥" + c.getAdjustedSurcharge() : "") + "：" + conclusion,
                cs, c.getReviewNo());
        return c;
    }

    private void resolveKeepPrice(UserAccount cs, ManualReviewCase c, GarmentException ex,
                                  GarmentItem item, QuoteRound disputed, String conclusion) {
        disputed.setStatus(QuoteRoundStatus.SUPERSEDED);
        roundRepo.save(disputed);
        ex.setStatus(ExceptionStatus.RESOLVED_KEEP_PRICE);
        ex.setResolution("人工复核维持原价：" + conclusion);
        ex.setResolvedAt(LocalDateTime.now());
        exceptionRepo.save(ex);
        BigDecimal base = pricing.basePriceOf(item);
        item.setFinalPrice(base);
        sm.transit(item, GarmentStatus.CONFIRMED, "人工复核维持原价");
        itemRepo.save(item);
        capacity.occupy(item, 0);
        OrderFlowSupport.advanceIfNoHolds(orderRepo, itemRepo, sm, ex.getOrderId());
    }

    private void resolveBasicOnly(UserAccount cs, ManualReviewCase c, GarmentException ex,
                                  GarmentItem item, QuoteRound disputed, String conclusion) {
        disputed.setStatus(QuoteRoundStatus.SUPERSEDED);
        roundRepo.save(disputed);
        ex.setStatus(ExceptionStatus.BASIC_ONLY);
        ex.setResolution("人工复核仅基础处理：" + conclusion);
        ex.setResolvedAt(LocalDateTime.now());
        exceptionRepo.save(ex);
        BigDecimal basic = PricingService.priceOf(ProcessType.BASIC);
        item.setFinalPrice(basic);
        item.setProcessType(ProcessType.BASIC);
        sm.transit(item, GarmentStatus.BASIC_ONLY, "人工复核仅基础处理");
        sm.transit(item, GarmentStatus.CONFIRMED, "基础处理放行");
        itemRepo.save(item);
        capacity.occupy(item, 0);
        OrderFlowSupport.advanceIfNoHolds(orderRepo, itemRepo, sm, ex.getOrderId());
    }

    private void resolveReject(UserAccount cs, ManualReviewCase c, GarmentException ex,
                               GarmentItem item, QuoteRound disputed, String conclusion) {
        disputed.setStatus(QuoteRoundStatus.SUPERSEDED);
        roundRepo.save(disputed);
        ex.setStatus(ExceptionStatus.REJECTED);
        ex.setResolution("人工复核拒绝承接：" + conclusion);
        ex.setResolvedAt(LocalDateTime.now());
        exceptionRepo.save(ex);
        sm.transit(item, GarmentStatus.REJECTED, "人工复核拒单");
        itemRepo.save(item);
        // 释放该件全部预授权（含客户曾确认追加的授权），已请款则原路退款
        BigDecimal locked = roundRepo.findByExceptionIdOrderByVersionAsc(ex.getId()).stream()
                .map(r -> r.getBasePrice().add(r.getSurchargeAmount()))
                .max(BigDecimal::compareTo)
                .orElse(pricing.basePriceOf(item));
        if (item.getCapturedAmount().signum() > 0) {
            payment.refund(ex.getOrderId(), item.getCapturedAmount(),
                    "refund-review-" + item.getId(), "人工复核拒单原路退回");
        } else {
            payment.releaseAmount(ex.getOrderId(), locked,
                    "release-review-" + item.getId(), "人工复核拒单释放预授权");
        }
        capacity.release(item);
        recovery.dispatch(item.getId(), ex.getId(), "REJECT_ORDER", "ex" + ex.getId() + "-REJECT_ORDER");
        OrderFlowSupport.advanceIfNoHolds(orderRepo, itemRepo, sm, ex.getOrderId());
    }
}
