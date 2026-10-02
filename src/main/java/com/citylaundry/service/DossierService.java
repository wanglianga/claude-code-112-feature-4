package com.citylaundry.service;

import com.citylaundry.common.ApiException;
import com.citylaundry.model.*;
import com.citylaundry.model.Enums.*;
import com.citylaundry.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 取洗档案聚合：把下单信息、衣物照片、材质/品牌、污渍、服务项、预估价格、
 * 骑手取件记录、门店质检、异常证据/研判/报价版本/客户反馈、履约状态、
 * 支付/退款、回收、产能、赔付、责任归属与审计时间线聚合为同一份可核对事实。
 */
@Service
public class DossierService {
    private final LaundryOrderRepo orderRepo;
    private final GarmentItemRepo itemRepo;
    private final GarmentPhotoRepo photoRepo;
    private final PickupRecordRepo pickupRepo;
    private final StoreQcRecordRepo qcRepo;
    private final GarmentExceptionRepo exceptionRepo;
    private final ExceptionEvidenceRepo evidenceRepo;
    private final ExceptionAssessmentRepo assessmentRepo;
    private final QuoteRoundRepo roundRepo;
    private final QuoteOptionRepo optionRepo;
    private final CustomerFeedbackRepo feedbackRepo;
    private final PaymentOrderRepo paymentRepo;
    private final PaymentEventRepo eventRepo;
    private final RecoveryTaskRepo recoveryRepo;
    private final ProcessRecordRepo processRepo;
    private final TransferRecordRepo transferRepo;
    private final CompensationCaseRepo caseRepo;
    private final AfterSalesRecordRepo afterSalesRepo;
    private final SettlementRepo settlementRepo;
    private final ManualSettlementBlockRepo blockRepo;
    private final ManualReviewCaseRepo reviewRepo;
    private final TimelineService timeline;
    private final PricingService pricing;

    public DossierService(LaundryOrderRepo orderRepo, GarmentItemRepo itemRepo,
                          GarmentPhotoRepo photoRepo, PickupRecordRepo pickupRepo,
                          StoreQcRecordRepo qcRepo, GarmentExceptionRepo exceptionRepo,
                          ExceptionEvidenceRepo evidenceRepo, ExceptionAssessmentRepo assessmentRepo,
                          QuoteRoundRepo roundRepo, QuoteOptionRepo optionRepo,
                          CustomerFeedbackRepo feedbackRepo, PaymentOrderRepo paymentRepo,
                          PaymentEventRepo eventRepo, RecoveryTaskRepo recoveryRepo,
                          ProcessRecordRepo processRepo, TransferRecordRepo transferRepo,
                          CompensationCaseRepo caseRepo, AfterSalesRecordRepo afterSalesRepo,
                          SettlementRepo settlementRepo, ManualSettlementBlockRepo blockRepo,
                          ManualReviewCaseRepo reviewRepo, TimelineService timeline,
                          PricingService pricing) {
        this.orderRepo = orderRepo;
        this.itemRepo = itemRepo;
        this.photoRepo = photoRepo;
        this.pickupRepo = pickupRepo;
        this.qcRepo = qcRepo;
        this.exceptionRepo = exceptionRepo;
        this.evidenceRepo = evidenceRepo;
        this.assessmentRepo = assessmentRepo;
        this.roundRepo = roundRepo;
        this.optionRepo = optionRepo;
        this.feedbackRepo = feedbackRepo;
        this.paymentRepo = paymentRepo;
        this.eventRepo = eventRepo;
        this.recoveryRepo = recoveryRepo;
        this.processRepo = processRepo;
        this.transferRepo = transferRepo;
        this.caseRepo = caseRepo;
        this.afterSalesRepo = afterSalesRepo;
        this.settlementRepo = settlementRepo;
        this.blockRepo = blockRepo;
        this.reviewRepo = reviewRepo;
        this.timeline = timeline;
        this.pricing = pricing;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> ofOrder(Long orderId) {
        LaundryOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> ApiException.notFound("订单不存在"));
        List<GarmentItem> items = itemRepo.findByOrderIdOrderByIdAsc(orderId);

        List<Map<String, Object>> itemCards = new ArrayList<>();
        Responsibility orderResponsibility = Responsibility.PENDING;
        for (GarmentItem item : items) {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("item", item);
            card.put("estimatePrice", pricing.basePriceOf(item));
            card.put("photos", photoRepo.findByItemIdOrderByCreatedAtAsc(item.getId()));
            card.put("processes", processRepo.findByItemIdOrderByStartedAtAsc(item.getId()));
            card.put("transfers", transferRepo.findByItemIdOrderByCreatedAtAsc(item.getId()));
            card.put("qc", qcRepo.findByItemId(item.getId()).orElse(null));
            card.put("afterSales", afterSalesRepo.findByItemId(item.getId()).orElse(null));
            card.put("compensations", caseRepo.findByItemId(item.getId()));

            List<RecoveryTask> recTasks = recoveryRepo.findByItemIdOrderByCreatedAtAsc(item.getId());
            card.put("recoveryTasks", recTasks);

            List<Map<String, Object>> exCards = new ArrayList<>();
            for (GarmentException ex : exceptionRepo.findByItemIdOrderByCreatedAtAsc(item.getId())) {
                Map<String, Object> em = new LinkedHashMap<>();
                em.put("exception", ex);
                em.put("evidence", evidenceRepo.findByExceptionIdOrderByRoundNoAscIdAsc(ex.getId()));
                em.put("assessments", assessmentRepo.findByExceptionIdOrderByAssessedAtAsc(ex.getId()));
                List<Map<String, Object>> rounds = new ArrayList<>();
                for (QuoteRound r : roundRepo.findByExceptionIdOrderByVersionAsc(ex.getId())) {
                    Map<String, Object> rm = new LinkedHashMap<>();
                    rm.put("round", r);
                    rm.put("options", optionRepo.findByQuoteRoundIdOrderByIdAsc(r.getId()));
                    rounds.add(rm);
                }
                em.put("quoteRounds", rounds);
                em.put("feedbacks", feedbackRepo.findByExceptionIdOrderByCreatedAtAsc(ex.getId()));
                em.put("manualReviews", reviewRepo.findByExceptionIdOrderByCreatedAtAsc(ex.getId()));
                exCards.add(em);
                if (ex.getResponsibility() != Responsibility.PENDING) {
                    orderResponsibility = mergeResponsibility(orderResponsibility, ex.getResponsibility());
                }
            }
            card.put("exceptions", exCards);
            itemCards.add(card);
        }

        PaymentOrder payment = paymentRepo.findByOrderId(orderId).orElse(null);
        Map<String, Object> paymentBlock = null;
        if (payment != null) {
            paymentBlock = new LinkedHashMap<>();
            paymentBlock.put("payment", payment);
            paymentBlock.put("events", eventRepo.findByPaymentIdOrderByIdAsc(payment.getId()));
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("order", order);
        resp.put("pickupRecords", pickupRepo.findByOrderIdOrderByPickedAtAsc(orderId));
        resp.put("items", itemCards);
        resp.put("payment", paymentBlock);
        resp.put("settlement", settlementRepo.findByOrderId(orderId).orElse(null));
        resp.put("closeoutBlocks", blockRepo.findByOrderIdAndStatusOrderByIdAsc(orderId, CheckStatus.MISSING));
        resp.put("responsibility", orderResponsibility);
        resp.put("timeline", timeline.ofOrder(orderId));
        return resp;
    }

    private Responsibility mergeResponsibility(Responsibility a, Responsibility b) {
        if (a == Responsibility.PENDING) return b;
        return a == b ? a : Responsibility.SHARED;
    }
}
