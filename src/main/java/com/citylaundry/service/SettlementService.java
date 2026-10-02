package com.citylaundry.service;

import com.citylaundry.common.ApiException;
import com.citylaundry.model.*;
import com.citylaundry.model.Enums.*;
import com.citylaundry.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 关单校验与自动结算：
 * 逐件校验「证据 / 客户确认 / 质检结论 / 包装与取回记录 / 支付结果」是否齐全，
 * 任一缺项即生成 {@link SettlementBlock} 阻断自动结算并转入客服人工队列；
 * 全部齐全才允许结算（订单 SETTLED）。结算本身幂等。
 */
@Service
public class SettlementService {
    private final SettlementBlockRepo blockRepo;
    private final LaundryOrderRepo orderRepo;
    private final GarmentItemRepo itemRepo;
    private final AnomalyIssueRepo issueRepo;
    private final IssueEvidenceRepo evidenceRepo;
    private final CustomerDecisionRepo decisionRepo;
    private final StoreQcRecordRepo qcRepo;
    private final ProcessRecordRepo processRepo;
    private final PaymentRepo paymentRepo;
    private final GarmentPhotoRepo photoRepo;
    private final IdempotentRequestRepo idemRepo;

    public SettlementService(SettlementBlockRepo blockRepo, LaundryOrderRepo orderRepo,
                             GarmentItemRepo itemRepo, AnomalyIssueRepo issueRepo,
                             IssueEvidenceRepo evidenceRepo, CustomerDecisionRepo decisionRepo,
                             StoreQcRecordRepo qcRepo, ProcessRecordRepo processRepo,
                             PaymentRepo paymentRepo, GarmentPhotoRepo photoRepo,
                             IdempotentRequestRepo idemRepo) {
        this.blockRepo = blockRepo;
        this.orderRepo = orderRepo;
        this.itemRepo = itemRepo;
        this.issueRepo = issueRepo;
        this.evidenceRepo = evidenceRepo;
        this.decisionRepo = decisionRepo;
        this.qcRepo = qcRepo;
        this.processRepo = processRepo;
        this.paymentRepo = paymentRepo;
        this.photoRepo = photoRepo;
        this.idemRepo = idemRepo;
    }

    /** 逐件关单校验，返回每件缺项；missing 为空表示该件通过 */
    @Transactional(readOnly = true)
    public Map<String, Object> checkItem(Long itemId) {
        GarmentItem item = itemRepo.findById(itemId).orElseThrow(() -> ApiException.notFound("衣物不存在"));
        List<String> missing = new ArrayList<>();
        List<String> details = new ArrayList<>();
        boolean returned = item.getStatus() == GarmentStatus.RETURNED;

        List<AnomalyIssue> issues = issueRepo.findByItemIdOrderByCreatedAtAsc(itemId);
        List<GarmentPhoto> photos = photoRepo.findByItemIdOrderByCreatedAtAsc(itemId);

        // 1) 证据：每个异常项至少 1 张原始证据；异常必须已取得客户最终决策
        for (AnomalyIssue issue : issues) {
            if (evidenceRepo.countByIssueId(issue.getId()) < 1) {
                missing.add("EVIDENCE");
                details.add("异常" + issue.getIssueNo() + "缺证据照片");
            }
            boolean unresolved = issue.getStatus() == AnomalyStatus.OPEN
                    || issue.getStatus() == AnomalyStatus.QUOTED || issue.getStatus() == AnomalyStatus.REVIEW;
            if (!returned && unresolved) {
                missing.add("CUSTOMER_CONFIRM");
                details.add("异常" + issue.getIssueNo() + "未取得客户最终确认(" + issue.getStatus() + ")");
            }
            if (returned && unresolved) {
                missing.add("CUSTOMER_CONFIRM");
                details.add("异常" + issue.getIssueNo() + "尚未处理完毕");
            }
            // DECIDED（客户确认追加/拒绝退回）须能找到客户决策记录
            if (!returned && issue.getStatus() == AnomalyStatus.DECIDED
                    && decisionRepo.findByIssueIdOrderByCreatedAtAsc(issue.getId()).isEmpty()) {
                missing.add("CUSTOMER_CONFIRM");
                details.add("异常" + issue.getIssueNo() + "缺客户决策记录");
            }
        }
        // 收衣原始证据（正/反面）
        if (!returned && photos.stream().noneMatch(p -> p.getStage() == PhotoStage.PICKUP_FRONT)
                && photos.stream().noneMatch(p -> p.getStage() == PhotoStage.PICKUP_BACK)) {
            missing.add("EVIDENCE");
            details.add("缺收衣正反面原始照片");
        }

        // 2) 客户确认：有追加金额必须存在客户 CONFIRMED 决策
        if (item.getAgreedExtra() != null && item.getAgreedExtra().compareTo(BigDecimal.ZERO) > 0) {
            boolean confirmed = decisionRepo.findByItemIdOrderByCreatedAtAsc(itemId).stream()
                    .anyMatch(d -> d.getStatus() == DecisionStatus.CONFIRMED);
            if (!confirmed) {
                missing.add("CUSTOMER_CONFIRM");
                details.add("追加报价 ¥" + item.getAgreedExtra() + " 缺客户确认");
            }
        }

        // 3) 质检结论
        List<StoreQcRecord> intakes = qcRepo.findByItemIdAndPhaseOrderByCreatedAtAsc(itemId, "INTAKE");
        QcConclusion intake = intakes.isEmpty() ? null : intakes.get(intakes.size() - 1).getConclusion();
        if (!returned) {
            boolean processed = !processRepo.findByItemIdOrderByStartedAtAsc(itemId).isEmpty();
            if (intake == null && !processed) {
                missing.add("QC_RESULT"); details.add("缺入厂质检结论且无洗护记录");
            } else if (intake == QcConclusion.ANOMALY || intake == QcConclusion.PENDING || intake == QcConclusion.REJECT) {
                missing.add("QC_RESULT"); details.add("入厂质检结论未通过: " + intake);
            }
            StoreQcRecord finalQc = latestFinal(itemId);
            if (finalQc == null || finalQc.getConclusion() != QcConclusion.PASS) {
                missing.add("QC_RESULT");
                details.add("关单前复检未通过或未做（包装/取回须确认齐全）");
            }
        }
        // 退回件未实际入厂洗护，不要求入厂/复检结论，证据与回收留影即足够

        // 4) 包装 / 取回记录
        if (returned) {
            boolean reclaimPhoto = photos.stream().anyMatch(p -> p.getStage() == PhotoStage.RECLAIM);
            if (!reclaimPhoto) { missing.add("HANDOVER"); details.add("退回件缺骑手回收留影"); }
        } else {
            StoreQcRecord finalQc = latestFinal(itemId);
            if (finalQc == null || !finalQc.isPackagingConfirmed()) {
                missing.add("PACKAGING"); details.add("缺包装确认");
            }
            if (finalQc == null || !finalQc.isHandoverConfirmed()) {
                missing.add("HANDOVER"); details.add("缺交付/交接记录确认");
            }
        }

        // 5) 支付结果
        List<Payment> pays = paymentRepo.findByItemIdOrderByCreatedAtAsc(itemId);
        if (returned) {
            for (Payment p : pays) {
                if (p.getStatus() != PaymentStatus.REFUNDED && p.getStatus() != PaymentStatus.RELEASED) {
                    missing.add("PAYMENT"); details.add("支付单" + p.getPaymentNo() + "未完成退回(" + p.getStatus() + ")");
                }
            }
        } else {
            boolean captured = pays.stream().anyMatch(p -> p.getStatus() == PaymentStatus.CAPTURED);
            if (!captured) {
                missing.add("PAYMENT");
                Payment bad = pays.stream()
                        .filter(p -> p.getStatus() == PaymentStatus.FAILED || p.getStatus() == PaymentStatus.PENDING)
                        .findFirst().orElse(null);
                details.add(bad == null ? "缺成功扣款记录"
                        : "支付回调未成功(" + bad.getStatus() + (bad.getFailReason() == null ? "" : ":" + bad.getFailReason()) + ")");
            }
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("itemId", itemId);
        r.put("itemCode", item.getItemCode());
        r.put("status", item.getStatus());
        r.put("passed", missing.isEmpty());
        r.put("missingItems", new ArrayList<>(new LinkedHashSet<>(missing)));
        r.put("details", details);
        return r;
    }

    private StoreQcRecord latestFinal(Long itemId) {
        List<StoreQcRecord> list = qcRepo.findByItemIdAndPhaseOrderByCreatedAtAsc(itemId, "FINAL");
        return list.isEmpty() ? null : list.get(list.size() - 1);
    }

    /**
     * 执行关单：逐件校验；缺项则（幂等）登记/更新人工队列阻断项并返回 BLOCKED；
     * 全部齐全则订单 SETTLED（幂等，重复结算不重复操作）。
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> closeOrder(Long operatorId) {
        return closeOrder(null, operatorId);
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> closeOrder(Long orderId, Long operatorId) {
        List<LaundryOrder> orders = orderId == null
                ? orderRepo.findAll().stream().filter(o -> o.getStatus() == OrderStatus.DELIVERED
                        || o.getStatus() == OrderStatus.PARTIALLY_RETURNED).toList()
                : List.of(orderRepo.findById(orderId).orElseThrow(() -> ApiException.notFound("订单不存在")));

        List<Map<String, Object>> results = new ArrayList<>();
        int blockedOrders = 0, settledOrders = 0;
        for (LaundryOrder order : orders) {
            if (order.getStatus() == OrderStatus.SETTLED) {
                Map<String, Object> ok = new LinkedHashMap<>();
                ok.put("orderId", order.getId()); ok.put("result", "ALREADY_SETTLED");
                results.add(ok);
                settledOrders++;
                continue;
            }
            List<Map<String, Object>> itemResults = new ArrayList<>();
            List<String> orderMissing = new ArrayList<>();
            for (GarmentItem item : itemRepo.findByOrderIdOrderByIdAsc(order.getId())) {
                Map<String, Object> cr = checkItem(item.getId());
                itemResults.add(cr);
                if (!(boolean) cr.get("passed")) orderMissing.addAll((List<String>) cr.get("missingItems"));
            }

            if (orderMissing.isEmpty()) {
                settle(order);
                Map<String, Object> ok = new LinkedHashMap<>();
                ok.put("orderId", order.getId());
                ok.put("orderNo", order.getOrderNo());
                ok.put("result", "SETTLED");
                ok.put("items", itemResults);
                results.add(ok);
                settledOrders++;
            } else {
                order.setSettlementBlocked(true);
                order.setSettlementBlockReason("关单缺项: " + String.join(",", new LinkedHashSet<>(orderMissing)));
                orderRepo.save(order);
                for (Map<String, Object> cr : itemResults) {
                    if (!(boolean) cr.get("passed")) upsertBlock(order.getId(), cr);
                }
                Map<String, Object> bl = new LinkedHashMap<>();
                bl.put("orderId", order.getId());
                bl.put("orderNo", order.getOrderNo());
                bl.put("result", "BLOCKED");
                bl.put("missingItems", new ArrayList<>(new LinkedHashSet<>(orderMissing)));
                bl.put("items", itemResults);
                results.add(bl);
                blockedOrders++;
            }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("settledOrders", settledOrders);
        resp.put("blockedOrders", blockedOrders);
        resp.put("results", results);
        return resp;
    }

    private void settle(LaundryOrder order) {
        String key = "SETTLE:" + order.getId();
        if (idemRepo.findByActionAndIdempotencyKey("SETTLE", key).isEmpty()) {
            IdempotentRequest ir = new IdempotentRequest();
            ir.setAction("SETTLE");
            ir.setIdempotencyKey(key);
            ir.setResourceId(order.getId());
            ir.setResultRef(order.getOrderNo());
            idemRepo.save(ir);
        }
        // 解决历史阻断项
        for (SettlementBlock b : blockRepo.findByOrderIdOrderByCreatedAtAsc(order.getId())) {
            if (!b.isResolved()) {
                b.setResolved(true);
                b.setResolvedAt(LocalDateTime.now());
                b.setHandleNote("关单校验通过，自动解除阻断");
                blockRepo.save(b);
            }
        }
        order.setSettlementBlocked(false);
        order.setSettlementBlockReason(null);
        order.setStatus(OrderStatus.SETTLED);
        order.setSettledAt(LocalDateTime.now());
        orderRepo.save(order);
    }

    @SuppressWarnings("unchecked")
    private void upsertBlock(Long orderId, Map<String, Object> cr) {
        Long itemId = ((Number) cr.get("itemId")).longValue();
        String missing = String.join(",", (List<String>) cr.get("missingItems"));
        String detail = String.join(";", (List<String>) cr.get("details"));
        List<SettlementBlock> exists = blockRepo.findByItemIdOrderByCreatedAtAsc(itemId).stream()
                .filter(b -> !b.isResolved()).toList();
        SettlementBlock b;
        if (exists.isEmpty()) {
            b = new SettlementBlock();
            b.setOrderId(orderId);
            b.setItemId(itemId);
        } else {
            b = exists.get(0);
        }
        b.setResult(CloseCheckStatus.BLOCKED);
        b.setMissingItems(missing);
        b.setDetail(detail);
        blockRepo.save(b);
    }

    /** 人工队列：未解决的关单阻断项 */
    public List<SettlementBlock> manualQueue() {
        return blockRepo.findByResolvedFalseOrderByCreatedAtAsc();
    }

    /** 客服补齐材料后处理阻断项（备注）；重新关单校验通过才真正解除 */
    @Transactional
    public SettlementBlock handleBlock(UserAccount cs, Long blockId, String note) {
        SettlementBlock b = blockRepo.findById(blockId)
                .orElseThrow(() -> ApiException.notFound("阻断项不存在"));
        b.setHandledBy(cs.getId());
        b.setHandleNote(note);
        return blockRepo.save(b);
    }

    @Transactional
    public SettlementBlock resolveBlock(UserAccount cs, Long blockId, String note) {
        SettlementBlock b = blockRepo.findById(blockId)
                .orElseThrow(() -> ApiException.notFound("阻断项不存在"));
        Map<String, Object> cr = checkItem(b.getItemId());
        if (!(boolean) cr.get("passed")) {
            throw ApiException.conflict("缺项仍未补齐，不能解除阻断: " + cr.get("missingItems")
                    + " " + cr.get("details"));
        }
        b.setResolved(true);
        b.setResult(CloseCheckStatus.PASS);
        b.setResolvedAt(LocalDateTime.now());
        b.setHandledBy(cs.getId());
        b.setHandleNote(note);
        return blockRepo.save(b);
    }
}
