package com.citylaundry.service;

import com.citylaundry.common.ApiException;
import com.citylaundry.model.*;
import com.citylaundry.model.Enums.*;
import com.citylaundry.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可审计取洗档案聚合：把同一件衣物 / 同一订单的
 * 下单信息、照片、材质品牌、污渍、服务项、估价/报价、骑手取件、门店质检、
 * 异常证据、报价决策、客户反馈、履约状态、支付/退款、责任归属聚合为同一份事实，
 * 供客服、门店、客户对同一事实核对。
 */
@Service
public class ArchiveService {
    private final LaundryOrderRepo orderRepo;
    private final GarmentItemRepo itemRepo;
    private final GarmentPhotoRepo photoRepo;
    private final ProcessRecordRepo processRepo;
    private final TransferRecordRepo transferRepo;
    private final ExceptionTicketRepo ticketRepo;
    private final CompensationCaseRepo caseRepo;
    private final AfterSalesRecordRepo afterSalesRepo;
    private final HighValueReviewRepo hvReviewRepo;
    private final AnomalyIssueRepo issueRepo;
    private final IssueEvidenceRepo evidenceRepo;
    private final QuoteVersionRepo quoteRepo;
    private final CustomerDecisionRepo decisionRepo;
    private final ManualReviewCaseRepo reviewRepo;
    private final PaymentRepo paymentRepo;
    private final StoreQcRecordRepo qcRepo;
    private final GarmentStatusEventRepo eventRepo;
    private final RiderReclaimRepo reclaimRepo;
    private final SettlementBlockRepo blockRepo;
    private final MemberTransactionRepo txRepo;

    public ArchiveService(LaundryOrderRepo orderRepo, GarmentItemRepo itemRepo, GarmentPhotoRepo photoRepo,
                          ProcessRecordRepo processRepo, TransferRecordRepo transferRepo,
                          ExceptionTicketRepo ticketRepo, CompensationCaseRepo caseRepo,
                          AfterSalesRecordRepo afterSalesRepo, HighValueReviewRepo hvReviewRepo,
                          AnomalyIssueRepo issueRepo, IssueEvidenceRepo evidenceRepo,
                          QuoteVersionRepo quoteRepo, CustomerDecisionRepo decisionRepo,
                          ManualReviewCaseRepo reviewRepo, PaymentRepo paymentRepo,
                          StoreQcRecordRepo qcRepo, GarmentStatusEventRepo eventRepo,
                          RiderReclaimRepo reclaimRepo, SettlementBlockRepo blockRepo,
                          MemberTransactionRepo txRepo) {
        this.orderRepo = orderRepo; this.itemRepo = itemRepo; this.photoRepo = photoRepo;
        this.processRepo = processRepo; this.transferRepo = transferRepo;
        this.ticketRepo = ticketRepo; this.caseRepo = caseRepo;
        this.afterSalesRepo = afterSalesRepo; this.hvReviewRepo = hvReviewRepo;
        this.issueRepo = issueRepo; this.evidenceRepo = evidenceRepo;
        this.quoteRepo = quoteRepo; this.decisionRepo = decisionRepo;
        this.reviewRepo = reviewRepo; this.paymentRepo = paymentRepo;
        this.qcRepo = qcRepo; this.eventRepo = eventRepo;
        this.reclaimRepo = reclaimRepo; this.blockRepo = blockRepo; this.txRepo = txRepo;
    }

    /** 单件衣物取洗档案 */
    @Transactional(readOnly = true)
    public Map<String, Object> garmentArchive(Long itemId) {
        GarmentItem item = itemRepo.findById(itemId).orElseThrow(() -> ApiException.notFound("衣物不存在"));
        LaundryOrder order = orderRepo.findById(item.getOrderId()).orElseThrow();

        Map<String, Object> a = new LinkedHashMap<>();
        // 1. 衣物明细 + 下单事实
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("itemCode", item.getItemCode());
        detail.put("type", item.getType());
        detail.put("orderBrand", item.getBrand());
        detail.put("orderMaterial", item.getMaterial());
        detail.put("color", item.getColor());
        detail.put("stains", item.getStains());
        detail.put("existingDamage", item.getExistingDamage());
        detail.put("accessories", item.getAccessories());
        detail.put("careLabelPresent", item.isCareLabelPresent());
        detail.put("declaredValue", item.getDeclaredValue());
        detail.put("luxury", item.isLuxury());
        detail.put("riskLevel", item.getRiskLevel());
        detail.put("basePrice", item.getBasePrice());
        detail.put("agreedExtra", item.getAgreedExtra());
        detail.put("expectedDeliveryAt", item.getExpectedDeliveryAt() != null
                ? item.getExpectedDeliveryAt() : order.getExpectedDeliveryAt());
        a.put("garment", detail);
        a.put("orderInfo", Map.of(
                "orderNo", order.getOrderNo(), "pickupAddress", order.getPickupAddress(),
                "deliveryAddress", order.getDeliveryAddress(), "estimatedAmount", nz(order.getEstimatedAmount()),
                "extraAmount", nz(order.getExtraAmount()), "totalAmount", nz(order.getTotalAmount())));

        // 2. 照片（含拍摄时间/位置；异常证据单列）
        a.put("photos", photoRepo.findByItemIdOrderByCreatedAtAsc(itemId));

        // 3. 骑手取件 / 交接记录
        a.put("pickupAndTransfers", transferRepo.findByItemIdOrderByCreatedAtAsc(itemId));

        // 4. 门店质检
        a.put("storeQc", qcRepo.findByItemIdOrderByCreatedAtAsc(itemId));

        // 5. 服务项 / 洗护工序
        a.put("processes", processRepo.findByItemIdOrderByStartedAtAsc(itemId));
        a.put("highValueReview", hvReviewRepo.findByItemId(itemId).orElse(null));

        // 6. 异常证据 + 报价版本 + 客户反馈 + 人工复核（原判断与历史版本全部保留）
        List<Map<String, Object>> issueList = new ArrayList<>();
        for (AnomalyIssue issue : issueRepo.findByItemIdOrderByCreatedAtAsc(itemId)) {
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("issue", issue);
            im.put("evidence", evidenceRepo.findByIssueIdOrderByShotRoundAsc(issue.getId()));
            im.put("quoteVersions", quoteRepo.findByIssueIdOrderByVersionNoAsc(issue.getId()));
            im.put("decisions", decisionRepo.findByIssueIdOrderByCreatedAtAsc(issue.getId()));
            im.put("manualReviews", reviewRepo.findByIssueIdOrderByCreatedAtAsc(issue.getId()));
            issueList.add(im);
        }
        a.put("anomalies", issueList);

        // 7. 协同工单与售后
        a.put("tickets", ticketRepo.findByItemIdOrderByCreatedAtAsc(itemId));
        a.put("afterSales", afterSalesRepo.findByItemId(itemId).orElse(null));

        // 8. 履约状态机（完整事件流）+ 回收
        a.put("statusEvents", eventRepo.findByItemIdOrderByIdAsc(itemId));
        a.put("currentStatus", item.getStatus());
        a.put("reclaim", item.getReclaimId() == null ? null : reclaimRepo.findById(item.getReclaimId()).orElse(null));

        // 9. 支付 / 退款
        a.put("payments", paymentRepo.findByItemIdOrderByCreatedAtAsc(itemId));

        // 10. 责任归属
        a.put("responsibility", responsibilityOf(item, issueList));

        // 11. 关单校验
        List<SettlementBlock> blocks = blockRepo.findByItemIdOrderByCreatedAtAsc(itemId);
        a.put("settlementBlocks", blocks);
        a.put("settlementClear", blocks.stream().allMatch(SettlementBlock::isResolved));
        return a;
    }

    /** 责任归属：以已立案/赔付的赔付案为准，未立案则按异常发现环节标注待判定 */
    private Map<String, Object> responsibilityOf(GarmentItem item, List<Map<String, Object>> issueList) {
        List<CompensationCase> cases = caseRepo.findByItemId(item.getId());
        Map<String, Object> r = new LinkedHashMap<>();
        List<Map<String, Object>> caseBrief = new ArrayList<>();
        String owner = "无";
        for (CompensationCase c : cases) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("caseNo", c.getCaseNo());
            b.put("responsibility", c.getResponsibility());
            b.put("amount", c.getAmount());
            b.put("status", c.getStatus());
            b.put("insuranceClaim", c.isInsuranceClaim());
            caseBrief.add(b);
            if (c.getStatus() == CompensationStatus.PAID || c.getStatus() == CompensationStatus.APPROVED
                    || c.getStatus() == CompensationStatus.REVIEWED || c.getStatus() == CompensationStatus.INSURANCE_FILED) {
                owner = c.getResponsibility();
            }
        }
        r.put("compensations", caseBrief);
        List<String> findings = new ArrayList<>();
        for (Map<String, Object> im : issueList) {
            AnomalyIssue issue = (AnomalyIssue) im.get("issue");
            findings.add(issue.getIssueNo() + ":" + issue.getType() + " 发现于"
                    + ("PICKUP".equals(issue.getFoundStage()) ? "骑手取件" : "门店质检"));
        }
        r.put("anomalyFindings", findings);
        r.put("owner", owner);
        r.put("note", "已决赔付案优先；无赔付案时责任待客服人工判定（异常发现环节已留痕）");
        return r;
    }

    /** 订单级取洗档案（逐件 + 订单支付汇总） */
    @Transactional(readOnly = true)
    public Map<String, Object> orderArchive(Long orderId) {
        LaundryOrder order = orderRepo.findById(orderId).orElseThrow(() -> ApiException.notFound("订单不存在"));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("order", order);
        List<Map<String, Object>> garments = new ArrayList<>();
        for (GarmentItem item : itemRepo.findByOrderIdOrderByIdAsc(orderId)) {
            garments.add(garmentArchive(item.getId()));
        }
        resp.put("garments", garments);
        resp.put("orderPayments", paymentRepo.findByOrderIdOrderByCreatedAtAsc(orderId));
        resp.put("cardTransactions", txRepo.findByOrderId(orderId));
        resp.put("settlementBlocked", order.isSettlementBlocked());
        resp.put("settlementBlockReason", order.getSettlementBlockReason());
        return resp;
    }

    private static java.math.BigDecimal nz(java.math.BigDecimal v) {
        return v == null ? java.math.BigDecimal.ZERO : v;
    }
}
