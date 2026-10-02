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
 * 关单前校验与自动结算。
 * 逐项校验：异常证据 / 客户确认 / 门店质检结论 / 包装记录 / 取回记录。
 * 任一缺项 → 阻止自动结算，订单转 MANUAL_REVIEW 并入人工队列；
 * 全部通过才允许 SETTLING → CLOSED。关单与结算幂等。
 */
@Service
public class CloseoutService {
    private final LaundryOrderRepo orderRepo;
    private final GarmentItemRepo itemRepo;
    private final GarmentPhotoRepo photoRepo;
    private final StoreQcRecordRepo qcRepo;
    private final GarmentExceptionRepo exceptionRepo;
    private final ExceptionEvidenceRepo evidenceRepo;
    private final CustomerFeedbackRepo feedbackRepo;
    private final RecoveryTaskRepo recoveryRepo;
    private final CompensationCaseRepo caseRepo;
    private final PaymentOrderRepo paymentRepo;
    private final ManualSettlementBlockRepo blockRepo;
    private final SettlementRepo settlementRepo;
    private final GarmentStateMachine sm;
    private final PaymentService payment;
    private final PricingService pricing;
    private final DeliveryService delivery;
    private final TimelineService timeline;

    public CloseoutService(LaundryOrderRepo orderRepo, GarmentItemRepo itemRepo,
                           GarmentPhotoRepo photoRepo, StoreQcRecordRepo qcRepo,
                           GarmentExceptionRepo exceptionRepo, ExceptionEvidenceRepo evidenceRepo,
                           CustomerFeedbackRepo feedbackRepo, RecoveryTaskRepo recoveryRepo,
                           CompensationCaseRepo caseRepo, PaymentOrderRepo paymentRepo,
                           ManualSettlementBlockRepo blockRepo, SettlementRepo settlementRepo,
                           GarmentStateMachine sm, PaymentService payment,
                           PricingService pricing, DeliveryService delivery,
                           TimelineService timeline) {
        this.orderRepo = orderRepo;
        this.itemRepo = itemRepo;
        this.photoRepo = photoRepo;
        this.qcRepo = qcRepo;
        this.exceptionRepo = exceptionRepo;
        this.evidenceRepo = evidenceRepo;
        this.feedbackRepo = feedbackRepo;
        this.recoveryRepo = recoveryRepo;
        this.caseRepo = caseRepo;
        this.paymentRepo = paymentRepo;
        this.blockRepo = blockRepo;
        this.settlementRepo = settlementRepo;
        this.sm = sm;
        this.payment = payment;
        this.pricing = pricing;
        this.delivery = delivery;
        this.timeline = timeline;
    }

    /** 仅跑校验，返回缺项（不改状态） */
    @Transactional(readOnly = true)
    public List<ManualSettlementBlock> evaluate(Long orderId) {
        LaundryOrder order = orderRepo.findById(orderId).orElseThrow(() -> ApiException.notFound("订单不存在"));
        List<GarmentItem> items = itemRepo.findByOrderIdOrderByIdAsc(orderId);
        List<ManualSettlementBlock> missing = new ArrayList<>();

        for (GarmentItem item : items) {
            boolean returned = item.getStatus() == GarmentStatus.RETURNED
                    || item.getStatus() == GarmentStatus.RECOVERED;
            boolean delivered = item.getStatus() == GarmentStatus.DELIVERED
                    || item.getStatus() == GarmentStatus.READY_FOR_DELIVERY
                    || item.getStatus() == GarmentStatus.CLOSED;

            // 1) 门店质检结论（每件都必须有）
            if (qcRepo.findByItemId(item.getId()).isEmpty()) {
                missing.add(block(orderId, item.getId(), CheckItemType.QC_CONCLUSION,
                        "衣物 " + item.getItemCode() + " 缺少门店收件质检结论"));
            }

            // 2) 异常证据与异常终态
            for (GarmentException ex : exceptionRepo.findByItemIdOrderByCreatedAtAsc(item.getId())) {
                List<ExceptionEvidence> evs = evidenceRepo
                        .findByExceptionIdOrderByRoundNoAscIdAsc(ex.getId());
                if (evs.isEmpty()) {
                    missing.add(block(orderId, item.getId(), CheckItemType.EVIDENCE,
                            "异常 " + ex.getExceptionNo() + " 缺少原始照片证据"));
                } else if (evs.stream().anyMatch(e -> e.getLocation() == null || e.getLocation().isBlank()
                        || e.getCapturedAt() == null)) {
                    missing.add(block(orderId, item.getId(), CheckItemType.EVIDENCE,
                            "异常 " + ex.getExceptionNo() + " 证据缺少拍摄时间或位置"));
                }
                if (!isTerminal(ex.getStatus())) {
                    missing.add(block(orderId, item.getId(), CheckItemType.CUSTOMER_CONFIRM,
                            "异常 " + ex.getExceptionNo() + " 未得到客户最终确认（状态 "
                                    + ex.getStatus() + "）"));
                }
            }

            // 3) 客户确认：有异常的衣物必须存在客户终局反馈（拒单退回除外，退回看取回记录）
            List<GarmentException> exs = exceptionRepo.findByItemIdOrderByCreatedAtAsc(item.getId());
            if (!exs.isEmpty() && !returned) {
                boolean decided = feedbackRepo.findByItemIdOrderByCreatedAtAsc(item.getId()).stream()
                        .anyMatch(f -> f.getAction() == CustomerAction.CONFIRM
                                || f.getAction() == CustomerAction.REJECT_CONTINUE);
                boolean manualResolved = exs.stream().allMatch(e ->
                        e.getStatus() == ExceptionStatus.CONFIRMED_SURCHARGE
                                || e.getStatus() == ExceptionStatus.RESOLVED_KEEP_PRICE
                                || e.getStatus() == ExceptionStatus.BASIC_ONLY);
                if (!decided && !manualResolved) {
                    missing.add(block(orderId, item.getId(), CheckItemType.CUSTOMER_CONFIRM,
                            "衣物 " + item.getItemCode() + " 缺少客户对报价的确认记录"));
                }
            }

            if (delivered && !returned) {
                // 4) 包装记录
                boolean packaged = photoRepo.findByItemIdOrderByCreatedAtAsc(item.getId()).stream()
                        .anyMatch(p -> p.getStage() == PhotoStage.PACKAGING);
                if (!packaged) {
                    missing.add(block(orderId, item.getId(), CheckItemType.PACKAGING,
                            "衣物 " + item.getItemCode() + " 缺少包装确认照片(PACKAGING)"));
                }
            }
            if (returned) {
                // 5) 取回记录：已完成的骑手回收任务 + 交接留影
                List<RecoveryTask> tasks = recoveryRepo.findByItemIdOrderByCreatedAtAsc(item.getId());
                boolean returnedOk = tasks.stream().anyMatch(t -> t.getStatus() == RecoveryStatus.DONE
                        && t.getHandoverPhotoUrl() != null && !t.getHandoverPhotoUrl().isBlank());
                if (!returnedOk) {
                    missing.add(block(orderId, item.getId(), CheckItemType.RETURN_RECORD,
                            "衣物 " + item.getItemCode() + " 已退回但缺少骑手取回交接记录/留影"));
                }
            }
        }
        return missing;
    }

    /**
     * 执行关单：校验 → 缺项转人工；齐全则对账结算并关单。幂等。
     */
    @Transactional
    public Map<String, Object> closeout(Long orderId, UserAccount actor) {
        LaundryOrder order = orderRepo.findById(orderId).orElseThrow(() -> ApiException.notFound("订单不存在"));
        Settlement existing = settlementRepo.findByOrderId(orderId).orElse(null);
        if (existing != null && existing.getStatus() == SettlementStatus.SETTLED) {
            return Map.of("settlement", existing, "blocks", List.of(), "idempotent", true);
        }

        List<ManualSettlementBlock> missing = evaluate(orderId);
        // 落库缺项（去重：同订单+衣物+校验项未解决不重复建）
        List<ManualSettlementBlock> persisted = new ArrayList<>();
        for (ManualSettlementBlock m : missing) {
            List<ManualSettlementBlock> open = blockRepo.findByOrderIdAndStatusOrderByIdAsc(
                    orderId, CheckStatus.MISSING);
            boolean dup = open.stream().anyMatch(b -> Objects.equals(b.getItemId(), m.getItemId())
                    && b.getCheckItem() == m.getCheckItem());
            if (!dup) persisted.add(blockRepo.save(m));
        }

        if (!persisted.isEmpty()) {
            if (order.getStatus() != OrderStatus.MANUAL_REVIEW
                    && sm.canOrder(order.getStatus(), OrderStatus.MANUAL_REVIEW)) {
                sm.transitOrder(order, OrderStatus.MANUAL_REVIEW, "关单缺项 " + persisted.size() + " 项");
                orderRepo.save(order);
            }
            Settlement s = existing == null ? new Settlement() : existing;
            s.setOrderId(orderId);
            s.setStatus(SettlementStatus.BLOCKED_MANUAL);
            s.setNote("自动结算被阻断，缺项 " + persisted.size() + " 项，已转人工队列");
            settlementRepo.save(s);
            timeline.add(orderId, null, "CLOSEOUT",
                    "关单校验缺项 " + persisted.size() + " 项，阻止自动结算并转人工", actor, null);
            return Map.of("settlement", s, "blocks", persisted, "idempotent", false);
        }

        // 全部通过 → 对账结算
        if (order.getStatus() != OrderStatus.SETTLING
                && sm.canOrder(order.getStatus(), OrderStatus.SETTLING)) {
            sm.transitOrder(order, OrderStatus.SETTLING, "关单校验通过");
            orderRepo.save(order);
        }

        List<GarmentItem> items = itemRepo.findByOrderIdOrderByIdAsc(orderId);
        // 应收以交付账单口径为准（含配送费，排除已退回件；客户确认成交价优先）
        @SuppressWarnings("unchecked")
        Map<String, Object> bill = (Map<String, Object>) delivery.billOf(orderId);
        BigDecimal receivable = (BigDecimal) bill.get("total");
        BigDecimal refunded = BigDecimal.ZERO;
        for (GarmentItem item : items) {
            Responsibility r = deriveResponsibility(item);
            attachResponsibility(item, r);
        }
        PaymentOrder paymentOrder = paymentRepo.findByOrderId(orderId).orElse(null);
        if (paymentOrder != null) {
            refunded = paymentOrder.getRefundedAmount();
            // 释放预授权中超出应收的冻结余额（估价冗余、退回件等）
            payment.releasePreauth(orderId, "closeout-release-" + orderId, "关单对账释放剩余预授权");
        }
        // 赔付补偿计入结算
        BigDecimal compensated = BigDecimal.ZERO;
        for (GarmentItem item : items) {
            for (CompensationCase c : caseRepo.findByItemId(item.getId())) {
                if (c.getStatus() == CompensationStatus.PAID || c.getStatus() == CompensationStatus.REVIEWED) {
                    compensated = compensated.add(c.getAmount());
                }
            }
        }

        Settlement s = existing == null ? new Settlement() : existing;
        s.setOrderId(orderId);
        s.setStatus(SettlementStatus.SETTLED);
        s.setFinalReceivable(receivable);
        s.setCapturedAmount(paymentOrder == null ? BigDecimal.ZERO : paymentOrder.getCapturedAmount());
        s.setRefundedAmount(refunded);
        s.setCompensatedAmount(compensated);
        s.setNote("证据/确认/质检/包装/取回记录齐全，自动结算完成");
        s.setSettledAt(LocalDateTime.now());
        settlementRepo.save(s);

        sm.transitOrder(order, OrderStatus.CLOSED, "结算完成关单");
        orderRepo.save(order);
        for (GarmentItem item : items) {
            if (item.getStatus() == GarmentStatus.DELIVERED) {
                sm.transit(item, GarmentStatus.CLOSED, "订单关单");
                itemRepo.save(item);
            } else if (item.getStatus() == GarmentStatus.RETURNED) {
                sm.transit(item, GarmentStatus.CLOSED, "退回件关单");
                itemRepo.save(item);
            }
        }
        timeline.add(orderId, null, "CLOSEOUT",
                "关单校验通过并自动结算：应收 ¥" + receivable + "，退款 ¥" + refunded
                        + "，补偿 ¥" + compensated + "，档案封存", actor, null);
        return Map.of("settlement", s, "blocks", List.of(), "idempotent", false);
    }

    /** 客服补齐缺项后标记解决，再重跑关单 */
    @Transactional
    public ManualSettlementBlock resolveBlock(UserAccount cs, Long blockId, String resolution) {
        ManualSettlementBlock b = blockRepo.findById(blockId)
                .orElseThrow(() -> ApiException.notFound("阻断项不存在"));
        if (b.getStatus() == CheckStatus.PASS) return b;
        if (resolution == null || resolution.isBlank()) throw ApiException.badRequest("处理说明不能为空");
        b.setStatus(CheckStatus.PASS);
        b.setHandledBy(cs.getId());
        b.setResolution(resolution);
        b.setResolvedAt(LocalDateTime.now());
        b = blockRepo.save(b);
        timeline.add(b.getOrderId(), b.getItemId(), "CLOSEOUT",
                "人工补齐缺项[" + b.getCheckItem() + "]：" + resolution, cs, null);

        List<ManualSettlementBlock> stillMissing = blockRepo
                .findByOrderIdAndStatusOrderByIdAsc(b.getOrderId(), CheckStatus.MISSING);
        if (stillMissing.isEmpty()) {
            LaundryOrder order = orderRepo.findById(b.getOrderId()).orElseThrow();
            Settlement st = settlementRepo.findByOrderId(order.getId()).orElse(null);
            if (st != null && st.getStatus() == SettlementStatus.BLOCKED_MANUAL) {
                if (order.getStatus() == OrderStatus.MANUAL_REVIEW) {
                    // 回到已交付态再走结算
                    order.setStatus(OrderStatus.DELIVERED);
                    orderRepo.save(order);
                }
                closeout(order.getId(), cs);
            }
        }
        return b;
    }

    @Transactional(readOnly = true)
    public List<ManualSettlementBlock> openBlocks() {
        return blockRepo.findByStatusOrderByCreatedAtAsc(CheckStatus.MISSING);
    }

    @Transactional(readOnly = true)
    public List<ManualSettlementBlock> blocksOf(Long orderId) {
        return blockRepo.findByOrderIdAndStatusOrderByIdAsc(orderId, CheckStatus.MISSING);
    }

    private boolean isTerminal(ExceptionStatus s) {
        return s == ExceptionStatus.CONFIRMED_SURCHARGE || s == ExceptionStatus.RESOLVED_KEEP_PRICE
                || s == ExceptionStatus.BASIC_ONLY || s == ExceptionStatus.REJECTED
                || s == ExceptionStatus.CLOSED;
    }

    /** 责任归属推导：依据异常类别、发现来源与终局结论 */
    private Responsibility deriveResponsibility(GarmentItem item) {
        List<GarmentException> exs = exceptionRepo.findByItemIdOrderByCreatedAtAsc(item.getId());
        Responsibility result = Responsibility.PENDING;
        for (GarmentException ex : exs) {
            Responsibility r;
            boolean storeRaised = ex.getSource() == ExceptionSource.STORE;
            boolean riderRaised = ex.getSource() == ExceptionSource.RIDER;
            switch (ex.getCategory()) {
                case MATERIAL_MISMATCH -> r = ex.getStatus() == ExceptionStatus.RESOLVED_KEEP_PRICE && storeRaised
                        ? Responsibility.STORE : Responsibility.CUSTOMER;
                case HIGH_RISK_STAIN, PERMANENT_FLAW -> r = Responsibility.CUSTOMER;
                case ACCESSORY_MISSING -> {
                    List<ExceptionEvidence> evs = evidenceRepo.findByExceptionIdOrderByRoundNoAscIdAsc(ex.getId());
                    boolean byStore = evs.stream().anyMatch(e -> e.getSource() == ExceptionSource.STORE);
                    boolean byRider = evs.stream().anyMatch(e -> e.getSource() == ExceptionSource.RIDER);
                    r = byStore && byRider ? Responsibility.SHARED : byStore ? Responsibility.RIDER
                            : Responsibility.CUSTOMER;
                }
                default -> r = Responsibility.PENDING;
            }
            if (r != Responsibility.PENDING) {
                result = result == Responsibility.PENDING ? r
                        : (result == r ? result : Responsibility.SHARED);
            }
        }
        // 已执行赔付案的责任优先采用
        for (CompensationCase c : caseRepo.findByItemId(item.getId())) {
            if (c.getStatus() == CompensationStatus.PAID || c.getStatus() == CompensationStatus.REVIEWED) {
                result = mapResp(c.getResponsibility(), result);
            }
        }
        return result;
    }

    private Responsibility mapResp(String s, Responsibility fallback) {
        try {
            return Responsibility.valueOf(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    private void attachResponsibility(GarmentItem item, Responsibility r) {
        for (GarmentException ex : exceptionRepo.findByItemIdOrderByCreatedAtAsc(item.getId())) {
            if (ex.getResponsibility() == Responsibility.PENDING && r != Responsibility.PENDING) {
                ex.setResponsibility(r);
                exceptionRepo.save(ex);
            }
        }
    }

    private ManualSettlementBlock block(Long orderId, Long itemId, CheckItemType type, String detail) {
        ManualSettlementBlock b = new ManualSettlementBlock();
        b.setOrderId(orderId);
        b.setItemId(itemId);
        b.setCheckItem(type);
        b.setStatus(CheckStatus.MISSING);
        b.setDetail(detail);
        return b;
    }
}
