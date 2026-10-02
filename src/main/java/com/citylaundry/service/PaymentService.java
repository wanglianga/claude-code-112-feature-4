package com.citylaundry.service;

import com.citylaundry.common.ApiException;
import com.citylaundry.model.*;
import com.citylaundry.model.Enums.PaymentIntent;
import com.citylaundry.model.Enums.PaymentStatus;
import com.citylaundry.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 支付服务（演示环境以会员卡模拟支付渠道，两阶段：预授权 → 扣款 → 渠道回调）。
 *
 * <p>幂等保证（避免重复扣款 / 重复退款）：
 * <ul>
 *   <li>建单：requestId 唯一，重复请求直接返回既有支付单；</li>
 *   <li>扣款请求：captureIdempotencyKey 唯一；</li>
 *   <li>渠道回调：callbackId 唯一，回调重复投递只生效一次；</li>
 *   <li>资金流水：member_transaction.idempotency_key 唯一兜底；</li>
 *   <li>退款：IdempotentRequest(REFUND, key) + 流水唯一键双重去重。</li>
 * </ul>
 *
 * 衣物已进入洗护但回调失败：支付停留在失败/处理中态，预授权冻结不释放、衣物状态不回退，
 * 由财务/系统重试回调；重试成功后只扣一次款。
 */
@Service
public class PaymentService {
    private final PaymentRepo paymentRepo;
    private final MemberCardRepo cardRepo;
    private final MemberTransactionRepo txRepo;
    private final GarmentItemRepo itemRepo;
    private final LaundryOrderRepo orderRepo;
    private final IdempotentRequestRepo idemRepo;

    public PaymentService(PaymentRepo paymentRepo, MemberCardRepo cardRepo,
                          MemberTransactionRepo txRepo, GarmentItemRepo itemRepo,
                          LaundryOrderRepo orderRepo, IdempotentRequestRepo idemRepo) {
        this.paymentRepo = paymentRepo;
        this.cardRepo = cardRepo;
        this.txRepo = txRepo;
        this.itemRepo = itemRepo;
        this.orderRepo = orderRepo;
        this.idemRepo = idemRepo;
    }

    private Payment number(Payment p) {
        p = paymentRepo.save(p);
        if ("TMP".equals(p.getPaymentNo())) {
            p.setPaymentNo(String.format("P%08d", p.getId()));
            p = paymentRepo.save(p);
        }
        return p;
    }

    private MemberTransaction postTx(Long cardId, Long orderId, Long paymentId, BigDecimal amount,
                                     String type, String key, String note) {
        if (key != null && txRepo.findByIdempotencyKey(key).isPresent()) {
            return txRepo.findByIdempotencyKey(key).get(); // 幂等：同键流水只入一次
        }
        MemberTransaction tx = new MemberTransaction();
        tx.setCardId(cardId);
        tx.setOrderId(orderId);
        tx.setPaymentId(paymentId);
        tx.setAmount(amount);
        tx.setType(type);
        tx.setIdempotencyKey(key);
        tx.setNote(note);
        return txRepo.save(tx);
    }

    /** 预授权：校验可用余额（余额-冻结），冻结金额，不实际扣款 */
    @Transactional
    public Payment preAuthorize(Long itemId, BigDecimal amount, String requestId) {
        return preAuthorize(itemId, amount, requestId, null);
    }

    /** 预授权（带业务引用，如 BASE:itemId / EXTRA:quoteVersionId） */
    @Transactional
    public Payment preAuthorize(Long itemId, BigDecimal amount, String requestId, String bizRef) {
        if (requestId == null || requestId.isBlank()) throw ApiException.badRequest("缺少幂等键 requestId");
        var existing = paymentRepo.findByRequestId(requestId);
        if (existing.isPresent()) return existing.get(); // 重复请求不重复冻结

        GarmentItem item = itemRepo.findById(itemId).orElseThrow(() -> ApiException.notFound("衣物不存在"));
        LaundryOrder order = orderRepo.findById(item.getOrderId()).orElseThrow();
        MemberCard card = cardRepo.findById(order.getMemberCardId()).orElseThrow();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
            throw ApiException.badRequest("预授权金额非法");
        }
        BigDecimal available = card.getBalance().subtract(card.getFrozenBalance());
        if (available.compareTo(amount) < 0) {
            throw ApiException.conflict("可用余额不足，无法预授权: 需 ¥" + amount
                    + "，可用 ¥" + available + "（含冻结 ¥" + card.getFrozenBalance() + "）");
        }

        Payment p = new Payment();
        p.setPaymentNo("TMP");
        p.setItemId(itemId);
        p.setOrderId(order.getId());
        p.setCardId(card.getId());
        p.setIntent(PaymentIntent.PRE_AUTH);
        p.setAmount(amount);
        p.setRequestId(requestId);
        p.setBizRef(bizRef);
        p.setStatus(PaymentStatus.AUTHORIZED);
        p.setAuthorizedAt(LocalDateTime.now());
        p = number(p);

        card.setFrozenBalance(card.getFrozenBalance().add(amount));
        cardRepo.save(card);
        postTx(card.getId(), order.getId(), p.getId(), amount, "PRE_AUTH_FREEZE",
                "freeze-" + p.getId(), "预授权冻结 衣物" + item.getItemCode() + " 支付单" + p.getPaymentNo());

        if (order.getPreAuthPaymentId() == null) {
            order.setPreAuthPaymentId(p.getId());
            orderRepo.save(order);
        }
        return p;
    }

    /**
     * 发起扣款（等待渠道回调）：冻结仍保留，支付进入 PENDING。
     * 用 captureIdempotencyKey 保证同一支付单不会被重复扣款。
     * captureAmount 为空=全额；小于预授权=部分扣款（回调成功后差额自动释放）。
     */
    @Transactional
    public Payment requestCapture(Long paymentId, String idempotencyKey) {
        return requestCapture(paymentId, idempotencyKey, null);
    }

    @Transactional
    public Payment requestCapture(Long paymentId, String idempotencyKey, BigDecimal captureAmount) {
        Payment p = getPayment(paymentId);
        if (p.getStatus() == PaymentStatus.CAPTURED) return p; // 已扣款：幂等返回
        if (p.getStatus() == PaymentStatus.PENDING && idempotencyKey != null
                && idempotencyKey.equals(p.getCaptureIdempotencyKey())) {
            return p; // 同一扣款请求重试
        }
        if (p.getStatus() != PaymentStatus.AUTHORIZED) {
            throw ApiException.conflict("支付单当前状态不能发起扣款: " + p.getStatus());
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw ApiException.badRequest("缺少扣款幂等键");
        }
        if (paymentRepo.findByCaptureIdempotencyKey(idempotencyKey).isPresent()) {
            return paymentRepo.findByCaptureIdempotencyKey(idempotencyKey).get();
        }
        if (captureAmount != null) {
            if (captureAmount.compareTo(BigDecimal.ZERO) < 0 || captureAmount.compareTo(p.getAmount()) > 0) {
                throw ApiException.badRequest("扣款金额超出预授权范围: ¥" + captureAmount + "/¥" + p.getAmount());
            }
            p.setPendingCaptureAmount(captureAmount);
        } else {
            p.setPendingCaptureAmount(p.getAmount());
        }
        p.setCaptureIdempotencyKey(idempotencyKey);
        p.setStatus(PaymentStatus.PENDING);
        return paymentRepo.save(p);
    }

    /**
     * 支付渠道回调（模拟）。callbackId 重复投递只处理一次。
     * success=true：解冻并实扣；success=false：回调失败，冻结保留、衣物不回退，可重试。
     */
    @Transactional
    public Payment handleCallback(Long paymentId, String callbackId, boolean success, String failReason) {
        Payment p = getPayment(paymentId);
        if (callbackId == null || callbackId.isBlank()) throw ApiException.badRequest("缺少渠道回调流水号 callbackId");

        // 同一回调流水号重复投递 → 原样返回（幂等）
        if (callbackId.equals(p.getCallbackId())) {
            return p;
        }
        if (paymentRepo.findByCallbackId(callbackId).isPresent()) {
            return paymentRepo.findByCallbackId(callbackId).get();
        }

        MemberCard card = cardRepo.findById(p.getCardId()).orElseThrow();
        if (success) {
            if (p.getStatus() == PaymentStatus.CAPTURED) return p;
            if (p.getStatus() != PaymentStatus.PENDING && p.getStatus() != PaymentStatus.AUTHORIZED
                    && p.getStatus() != PaymentStatus.FAILED) {
                throw ApiException.conflict("支付单当前状态不能确认扣款成功: " + p.getStatus());
            }
            BigDecimal capture = p.getPendingCaptureAmount() == null ? p.getAmount() : p.getPendingCaptureAmount();
            // 解冻全部预授权，实扣实际金额；部分扣款时差额仅解冻、不落账
            card.setFrozenBalance(card.getFrozenBalance().subtract(p.getAmount()).max(BigDecimal.ZERO));
            card.setBalance(card.getBalance().subtract(capture));
            cardRepo.save(card);

            p.setCapturedAmount(capture);
            p.setStatus(PaymentStatus.CAPTURED);
            p.setCallbackId(callbackId);
            p.setFailReason(null);
            p.setCapturedAt(LocalDateTime.now());
            p = paymentRepo.save(p);

            postTx(card.getId(), p.getOrderId(), p.getId(), capture.negate(), "PAYMENT",
                    "capture-" + p.getId(), "洗护扣款 支付单" + p.getPaymentNo()
                            + (capture.compareTo(p.getAmount()) < 0
                                ? "（部分扣款¥" + capture + "/预授权¥" + p.getAmount() + "，差额释放）" : ""));
        } else {
            // 回调失败：不扣款、不释放冻结，等待重试（衣物洗护不回退）
            p.setStatus(PaymentStatus.FAILED);
            p.setCallbackId(callbackId);
            p.setFailReason(failReason == null ? "渠道回调失败" : failReason);
            p = paymentRepo.save(p);
        }
        return p;
    }

    /** 释放未扣款的预授权（拒绝承接且尚未进入扣款流程） */
    @Transactional
    public Payment releasePreAuth(Long paymentId) {
        Payment p = getPayment(paymentId);
        if (p.getStatus() == PaymentStatus.RELEASED) return p;
        if (p.getStatus() == PaymentStatus.CAPTURED) {
            throw ApiException.conflict("支付单已实扣，不能释放，请走退款: " + p.getPaymentNo());
        }
        MemberCard card = cardRepo.findById(p.getCardId()).orElseThrow();
        card.setFrozenBalance(card.getFrozenBalance().subtract(p.getAmount()).max(BigDecimal.ZERO));
        cardRepo.save(card);

        p.setStatus(PaymentStatus.RELEASED);
        p.setReleasedAt(LocalDateTime.now());
        p = paymentRepo.save(p);
        postTx(card.getId(), p.getOrderId(), p.getId(), p.getAmount(), "PRE_AUTH_RELEASE",
                "release-" + p.getId(), "释放预授权 支付单" + p.getPaymentNo());
        return p;
    }

    /**
     * 退款（幂等）。已扣款走原路退回（余额加回）；未扣款则释放预授权。
     * idempotencyKey 相同的重复退款请求只执行一次。
     */
    @Transactional
    public Payment refund(Long paymentId, BigDecimal amount, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw ApiException.badRequest("缺少退款幂等键");
        }
        var idem = idemRepo.findByActionAndIdempotencyKey("REFUND", idempotencyKey);
        if (idem.isPresent()) {
            return paymentRepo.findById(idem.get().getResourceId()).orElseThrow(); // 重复退款：原样返回
        }
        Payment p = getPayment(paymentId);
        if (p.getStatus() == PaymentStatus.AUTHORIZED || p.getStatus() == PaymentStatus.PENDING
                || p.getStatus() == PaymentStatus.FAILED) {
            Payment released = releasePreAuth(p.getId());
            recordIdempotent("REFUND", idempotencyKey, p.getId(), released.getPaymentNo());
            return released;
        }
        if (p.getStatus() != PaymentStatus.CAPTURED && p.getStatus() != PaymentStatus.PARTIALLY_REFUNDED) {
            throw ApiException.conflict("支付单当前状态不能退款: " + p.getStatus());
        }
        BigDecimal refundAmount = amount == null ? p.getAmount().subtract(p.getRefundedAmount()) : amount;
        BigDecimal remaining = p.getAmount().subtract(p.getRefundedAmount());
        if (refundAmount.compareTo(remaining) > 0) {
            throw ApiException.badRequest("退款金额超过可退余额: 可退 ¥" + remaining);
        }

        MemberCard card = cardRepo.findById(p.getCardId()).orElseThrow();
        card.setBalance(card.getBalance().add(refundAmount));
        cardRepo.save(card);

        p.setRefundedAmount(p.getRefundedAmount().add(refundAmount));
        p.setStatus(p.getRefundedAmount().compareTo(p.getAmount()) >= 0
                ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED);
        p.setRefundedAt(LocalDateTime.now());
        p = paymentRepo.save(p);

        postTx(card.getId(), p.getOrderId(), p.getId(), refundAmount, "REFUND",
                "refund-" + idempotencyKey, "原路退回 支付单" + p.getPaymentNo());
        recordIdempotent("REFUND", idempotencyKey, p.getId(), p.getPaymentNo());
        return p;
    }

    void recordIdempotent(String action, String key, Long resourceId, String ref) {
        if (idemRepo.findByActionAndIdempotencyKey(action, key).isPresent()) return;
        IdempotentRequest r = new IdempotentRequest();
        r.setAction(action);
        r.setIdempotencyKey(key);
        r.setResourceId(resourceId);
        r.setResultRef(ref);
        idemRepo.save(r);
    }

    public Payment getPayment(Long id) {
        return paymentRepo.findById(id).orElseThrow(() -> ApiException.notFound("支付单不存在"));
    }

    public List<Payment> byItem(Long itemId) {
        return paymentRepo.findByItemIdOrderByCreatedAtAsc(itemId);
    }

    public List<Payment> byOrder(Long orderId) {
        return paymentRepo.findByOrderIdOrderByCreatedAtAsc(orderId);
    }
}
