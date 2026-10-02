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
 * 支付服务：预授权（冻结）→ 请款（扣款）→ 原路退回；支付/退款全部幂等。
 * 幂等两层保证：
 *  1. 渠道回调以 idempotencyKey 唯一落库（payment_event 唯一约束），事件只应用一次；
 *  2. 会员卡流水 member_transaction.idempotencyKey 唯一，重复请求直接返回不重复动钱。
 * 回调失败只记账不扣款，可在衣物已进入洗护后安全重试，重试不产生第二次扣款。
 */
@Service
public class PaymentService {
    private final PaymentOrderRepo paymentRepo;
    private final PaymentEventRepo eventRepo;
    private final MemberCardRepo cardRepo;
    private final MemberTransactionRepo txRepo;
    private final LaundryOrderRepo orderRepo;
    private final GarmentItemRepo itemRepo;
    private final TimelineService timeline;

    public PaymentService(PaymentOrderRepo paymentRepo, PaymentEventRepo eventRepo,
                          MemberCardRepo cardRepo, MemberTransactionRepo txRepo,
                          LaundryOrderRepo orderRepo, GarmentItemRepo itemRepo,
                          TimelineService timeline) {
        this.paymentRepo = paymentRepo;
        this.eventRepo = eventRepo;
        this.cardRepo = cardRepo;
        this.txRepo = txRepo;
        this.orderRepo = orderRepo;
        this.itemRepo = itemRepo;
        this.timeline = timeline;
    }

    /** 订单支付单（幂等：每单唯一） */
    @Transactional
    public PaymentOrder ensureForOrder(LaundryOrder order) {
        return paymentRepo.findByOrderId(order.getId()).orElseGet(() -> {
            PaymentOrder p = new PaymentOrder();
            p.setOrderId(order.getId());
            p.setCustomerId(order.getCustomerId());
            MemberCard card = cardRepo.findByCustomerId(order.getCustomerId())
                    .orElseThrow(() -> ApiException.badRequest("顾客没有会员卡"));
            p.setCardId(card.getId());
            p.setPaymentNo("P" + String.format("%08d", order.getId()));
            return paymentRepo.save(p);
        });
    }

    /** 预授权：冻结可用余额（不扣余额），重复调用幂等返回 */
    @Transactional
    public PaymentOrder preauth(Long orderId, BigDecimal amount, String key) {
        LaundryOrder order = orderRepo.findById(orderId).orElseThrow(() -> ApiException.notFound("订单不存在"));
        PaymentOrder p = ensureForOrder(order);
        if (p.getStatus() == PaymentStatus.PREAUTHED || p.getStatus() == PaymentStatus.CAPTURED
                || p.getStatus() == PaymentStatus.REFUNDED) {
            return p;
        }
        MemberCard card = cardRepo.findById(p.getCardId()).orElseThrow();
        if (card.getBalance().subtract(card.getFrozen()).compareTo(amount) < 0) {
            throw ApiException.conflict("会员卡可用余额不足，无法预授权 ¥" + amount);
        }
        card.setFrozen(card.getFrozen().add(amount));
        cardRepo.save(card);
        writeTx(card.getId(), orderId, p.getId(), null, amount.negate(), "PREAUTH",
                "订单 " + order.getOrderNo() + " 预授权冻结 ¥" + amount, keyOr(key, "preauth-" + p.getId()));
        p.setPreauthAmount(p.getPreauthAmount().add(amount));
        p.setStatus(PaymentStatus.PREAUTHED);
        p.setPreauthedAt(LocalDateTime.now());
        paymentRepo.save(p);
        timeline.add(orderId, null, "PAYMENT", "预授权冻结 ¥" + amount, 0L, "SYSTEM", p.getPaymentNo());
        return p;
    }

    /** 追加预授权：客户确认追加报价导致总额高于初始预授权时补冻结（幂等键防重复） */
    @Transactional
    public PaymentOrder increasePreauth(Long orderId, BigDecimal extra, String key) {
        if (extra == null || extra.signum() <= 0) {
            return paymentRepo.findByOrderId(orderId).orElse(null);
        }
        LaundryOrder order = orderRepo.findById(orderId).orElseThrow(() -> ApiException.notFound("订单不存在"));
        PaymentOrder p = paymentRepo.findByOrderId(orderId)
                .orElseThrow(() -> ApiException.conflict("订单尚无预授权，无法追加授权"));
        if (p.getStatus() != PaymentStatus.PREAUTHED && p.getStatus() != PaymentStatus.CAPTURED) {
            throw ApiException.conflict("支付单状态不允许追加预授权: " + p.getStatus());
        }
        MemberCard card = cardRepo.findById(p.getCardId()).orElseThrow();
        if (card.getBalance().subtract(card.getFrozen()).compareTo(extra) < 0) {
            throw ApiException.conflict("可用余额不足，无法追加预授权 ¥" + extra);
        }
        card.setFrozen(card.getFrozen().add(extra));
        cardRepo.save(card);
        writeTx(card.getId(), orderId, p.getId(), null, extra.negate(), "PREAUTH",
                "订单 " + order.getOrderNo() + " 追加预授权冻结 ¥" + extra,
                keyOr(key, "preauth-inc-" + p.getId()));
        p.setPreauthAmount(p.getPreauthAmount().add(extra));
        paymentRepo.save(p);
        timeline.add(orderId, null, "PAYMENT", "客户确认追加，补充预授权 ¥" + extra, 0L, "SYSTEM", p.getPaymentNo());
        return p;
    }

    /** 直接请款增量金额（流程内调用）：冻结转实扣，幂等键防重复扣款 */
    @Transactional
    public PaymentOrder capture(Long orderId, BigDecimal amount, String key) {
        if (amount == null || amount.signum() <= 0) {
            return paymentRepo.findByOrderId(orderId).orElseThrow(() -> ApiException.conflict("订单无支付单"));
        }
        PaymentOrder p = paymentRepo.findByOrderId(orderId)
                .orElseThrow(() -> ApiException.conflict("订单没有预授权支付单"));
        if (p.getStatus() != PaymentStatus.PREAUTHED && p.getStatus() != PaymentStatus.CAPTURED
                && p.getStatus() != PaymentStatus.RETRYING && p.getStatus() != PaymentStatus.FAILED) {
            throw ApiException.conflict("支付单状态不允许请款: " + p.getStatus());
        }
        if (frozenRemain(p).compareTo(amount) < 0) {
            throw ApiException.conflict("请款 ¥" + amount + " 超出可用预授权冻结 ¥" + frozenRemain(p));
        }
        MemberCard card = cardRepo.findById(p.getCardId()).orElseThrow();
        card.setFrozen(card.getFrozen().subtract(amount));
        card.setBalance(card.getBalance().subtract(amount));
        cardRepo.save(card);
        writeTx(card.getId(), orderId, p.getId(), null, amount.negate(), "CAPTURE",
                "订单请款扣款 ¥" + amount, keyOr(key, "capture-" + p.getId()));
        p.setCapturedAmount(p.getCapturedAmount().add(amount));
        p.setStatus(PaymentStatus.CAPTURED);
        p.setCapturedAt(LocalDateTime.now());
        p.setLastCallbackError(null);
        paymentRepo.save(p);
        timeline.add(orderId, null, "PAYMENT", "请款扣款 ¥" + amount, 0L, "SYSTEM", p.getPaymentNo());
        return p;
    }

    /** 原路退回：退款入会员卡。重复请求不产生第二次退款。 */
    @Transactional
    public PaymentOrder refund(Long orderId, BigDecimal amount, String key, String note) {
        if (amount == null || amount.signum() <= 0) {
            return paymentRepo.findByOrderId(orderId).orElse(null);
        }
        LaundryOrder order = orderRepo.findById(orderId).orElseThrow(() -> ApiException.notFound("订单不存在"));
        PaymentOrder p = paymentRepo.findByOrderId(orderId)
                .orElseThrow(() -> ApiException.conflict("订单没有支付单，无法退款"));
        if (p.getRefundedAmount().add(amount).compareTo(p.getCapturedAmount()) > 0) {
            throw ApiException.conflict("退款 ¥" + amount + " 超过可退金额 ¥"
                    + p.getCapturedAmount().subtract(p.getRefundedAmount()));
        }
        MemberCard card = cardRepo.findById(p.getCardId()).orElseThrow();
        card.setBalance(card.getBalance().add(amount));
        cardRepo.save(card);
        writeTx(card.getId(), orderId, p.getId(), null, amount, "REFUND",
                "原路退回 ¥" + amount + (note == null ? "" : "：" + note),
                keyOr(key, "refund-" + p.getId() + "-" + amount));
        p.setRefundedAmount(p.getRefundedAmount().add(amount));
        if (p.getRefundedAmount().compareTo(p.getCapturedAmount()) >= 0) {
            p.setStatus(PaymentStatus.REFUNDED);
            p.setRefundedAt(LocalDateTime.now());
        }
        paymentRepo.save(p);
        timeline.add(orderId, null, "REFUND",
                "原路退回 ¥" + amount + (note == null ? "" : "：" + note), 0L, "SYSTEM", p.getPaymentNo());
        return p;
    }

    /** 撤销预授权：释放全部剩余冻结（拒绝承接/客户拒绝未请款，或部分请款后的剩余额度） */
    @Transactional
    public PaymentOrder releasePreauth(Long orderId, String key, String note) {
        PaymentOrder p = paymentRepo.findByOrderId(orderId).orElse(null);
        if (p == null) return null;
        return doRelease(p, frozenRemain(p), key, note);
    }

    /**
     * 接收渠道回调（演示）。同一 idempotencyKey 重复回调只落一次账、只动一次钱。
     * 失败回调不动资金，支付单置 RETRYING（即使衣物已进入洗护也安全），随后可 retryCallback。
     */
    @Transactional
    public PaymentEvent receiveCallback(Long paymentId, PaymentType type, String idempotencyKey,
                                        BigDecimal amount, boolean success,
                                        String channelTxNo, String error) {
        PaymentOrder p = paymentRepo.findById(paymentId)
                .orElseThrow(() -> ApiException.notFound("支付单不存在"));
        PaymentEvent existing = eventRepo.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) {
            if (existing.isApplied()) return existing;        // 已成功，重复回调忽略
            if (!success) return existing;                    // 重复失败原样返回
            return applyCallback(p, existing, channelTxNo);   // 前次失败本次成功：只应用一次
        }
        PaymentEvent e = new PaymentEvent();
        e.setPaymentId(p.getId());
        e.setOrderId(p.getOrderId());
        e.setType(type);
        e.setIdempotencyKey(idempotencyKey);
        e.setAmount(amount == null ? BigDecimal.ZERO : amount);
        e.setChannelTxNo(channelTxNo);
        e.setCallbackFailed(!success);
        e.setErrorMessage(error);
        e.setResult(success ? successStatus(type) : PaymentStatus.FAILED);
        eventRepo.save(e);
        if (!success) {
            // 资金未动：支付单进入重试，不回滚任何业务（衣物可以已在洗护中）
            p.setStatus(PaymentStatus.RETRYING);
            p.setLastCallbackError(error);
            paymentRepo.save(p);
            timeline.add(p.getOrderId(), null, "PAYMENT",
                    "支付回调失败（资金未动，等待重试）: " + error, 0L, "SYSTEM", p.getPaymentNo());
            return e;
        }
        return applyCallback(p, e, channelTxNo);
    }

    /** 把一个回调事件的资金动作应用且仅应用一次（applied 标记 + 流水幂等键双保险） */
    private PaymentEvent applyCallback(PaymentOrder p, PaymentEvent e, String channelTxNo) {
        BigDecimal amount = e.getAmount();
        MemberCard card = cardRepo.findById(p.getCardId()).orElseThrow();
        switch (e.getType()) {
            case PREAUTH -> {
                if (p.getStatus() != PaymentStatus.PREAUTHED && p.getStatus() != PaymentStatus.CAPTURED) {
                    if (card.getBalance().subtract(card.getFrozen()).compareTo(amount) < 0) {
                        throw ApiException.conflict("可用余额不足，预授权回调无法应用");
                    }
                    card.setFrozen(card.getFrozen().add(amount));
                    cardRepo.save(card);
                    writeTx(card.getId(), p.getOrderId(), p.getId(), null, amount.negate(), "PREAUTH",
                            "渠道回调预授权冻结 ¥" + amount, "preauth-cb-" + e.getIdempotencyKey());
                    p.setPreauthAmount(p.getPreauthAmount().add(amount));
                    p.setStatus(PaymentStatus.PREAUTHED);
                    p.setPreauthedAt(LocalDateTime.now());
                    paymentRepo.save(p);
                }
            }
            case CAPTURE -> {
                if (frozenRemain(p).compareTo(amount) < 0) {
                    throw ApiException.conflict("可用预授权冻结不足 ¥" + amount + "，请人工核对");
                }
                card.setFrozen(card.getFrozen().subtract(amount));
                card.setBalance(card.getBalance().subtract(amount));
                cardRepo.save(card);
                writeTx(card.getId(), p.getOrderId(), p.getId(), null, amount.negate(), "CAPTURE",
                        "渠道回调请款 ¥" + amount, "capture-cb-" + e.getIdempotencyKey());
                p.setCapturedAmount(p.getCapturedAmount().add(amount));
                p.setStatus(PaymentStatus.CAPTURED);
                p.setCapturedAt(LocalDateTime.now());
                p.setLastCallbackError(null);
                paymentRepo.save(p);
                timeline.add(p.getOrderId(), null, "PAYMENT",
                        "渠道回调请款成功 ¥" + amount, 0L, "SYSTEM", p.getPaymentNo());
            }
            case REFUND -> {
                // 可退额 = 已请款 - 已退款；渠道回调不得退超过请款
                BigDecimal refundable = p.getCapturedAmount().subtract(p.getRefundedAmount());
                if (refundable.compareTo(amount) < 0) {
                    throw ApiException.conflict("渠道退款 ¥" + amount + " 超过可退额 ¥" + refundable);
                }
                card.setBalance(card.getBalance().add(amount));
                cardRepo.save(card);
                writeTx(card.getId(), p.getOrderId(), p.getId(), null, amount, "REFUND",
                        "渠道回调退款 ¥" + amount, "refund-cb-" + e.getIdempotencyKey());
                p.setRefundedAmount(p.getRefundedAmount().add(amount));
                p.setStatus(PaymentStatus.REFUNDED);
                p.setRefundedAt(LocalDateTime.now());
                paymentRepo.save(p);
                timeline.add(p.getOrderId(), null, "REFUND",
                        "渠道回调退款 ¥" + amount, 0L, "SYSTEM", p.getPaymentNo());
                // 全额退款后释放剩余预授权冻结（幂等）
                doRelease(p, frozenRemain(p), "release-refund-" + p.getId(), "全额退款后释放剩余预授权");
            }
            case COMPENSATION -> { }
        }
        e.setApplied(true);
        e.setAppliedAt(LocalDateTime.now());
        e.setCallbackFailed(false);
        e.setResult(successStatus(e.getType()));
        if (channelTxNo != null) e.setChannelTxNo(channelTxNo);
        return eventRepo.save(e);
    }

    /** 失败回调重放（同一幂等键告知成功，只应用一次资金动作） */
    @Transactional
    public PaymentEvent retryCallback(Long eventId) {
        PaymentEvent e = eventRepo.findById(eventId).orElseThrow(() -> ApiException.notFound("支付事件不存在"));
        if (e.isApplied()) return e;
        PaymentOrder p = paymentRepo.findById(e.getPaymentId()).orElseThrow();
        return applyCallback(p, e, e.getChannelTxNo());
    }

    /**
     * 按件请款的渠道回调编排：成功则把该件请款额记入衣物 capturedAmount（幂等）。
     * 典型场景：衣物已入厂/进入洗护，但渠道回调失败 → success=false 不动资金，
     * 支付单 RETRYING；之后重试同幂等键成功，只扣一次款。
     */
    @Transactional
    public PaymentEvent captureItemCallback(Long orderId, Long itemId, BigDecimal amount,
                                            String idempotencyKey, boolean success, String error) {
        PaymentOrder p = paymentRepo.findByOrderId(orderId)
                .orElseThrow(() -> ApiException.conflict("订单没有预授权支付单"));
        boolean wasApplied = eventRepo.findByIdempotencyKey(idempotencyKey)
                .map(PaymentEvent::isApplied).orElse(false);
        PaymentEvent e = receiveCallback(p.getId(), PaymentType.CAPTURE, idempotencyKey,
                amount, success, success ? "CH-" + idempotencyKey : null, error);
        if (success && !wasApplied && e.isApplied()) {
            GarmentItem item = itemRepo.findById(itemId).orElseThrow();
            item.setCapturedAmount(item.getCapturedAmount().add(amount));
            itemRepo.save(item);
        }
        return e;
    }

    /** 撤销/释放指定金额的预授权冻结（单件衣物退回且未请款时使用，幂等） */
    @Transactional
    public PaymentOrder releaseAmount(Long orderId, BigDecimal amount, String key, String note) {
        if (amount == null || amount.signum() <= 0) {
            return paymentRepo.findByOrderId(orderId).orElse(null);
        }
        PaymentOrder p = paymentRepo.findByOrderId(orderId)
                .orElseThrow(() -> ApiException.conflict("订单没有支付单"));
        BigDecimal release = amount.min(frozenRemain(p));
        return doRelease(p, release, key, note);
    }

    /** 支付单当前仍冻结的预授权额 = 预授权 - 已请款 - 已释放 */
    private BigDecimal frozenRemain(PaymentOrder p) {
        return p.getPreauthAmount()
                .subtract(p.getCapturedAmount())
                .subtract(p.getReleasedAmount() == null ? BigDecimal.ZERO : p.getReleasedAmount());
    }

    /** 释放指定冻结额（幂等键防重复；金额与已释放口径双保险） */
    private PaymentOrder doRelease(PaymentOrder p, BigDecimal release, String key, String note) {
        if (release == null || release.signum() <= 0) return p;
        MemberCard card = cardRepo.findById(p.getCardId()).orElseThrow();
        if (card.getFrozen().compareTo(release) < 0) release = card.getFrozen();
        if (release.signum() <= 0) return p;
        card.setFrozen(card.getFrozen().subtract(release));
        cardRepo.save(card);
        writeTx(card.getId(), p.getOrderId(), p.getId(), null, release, "PREAUTH_RELEASE",
                "释放预授权 ¥" + release + (note == null ? "" : "：" + note),
                keyOr(key, "release-" + p.getId() + "-" + release));
        p.setReleasedAmount(p.getReleasedAmount().add(release));
        if (frozenRemain(p).signum() <= 0) {
            p.setStatus(p.getCapturedAmount().signum() > 0 ? PaymentStatus.CAPTURED : PaymentStatus.CANCELLED);
        }
        paymentRepo.save(p);
        timeline.add(p.getOrderId(), null, "PAYMENT",
                "释放预授权 ¥" + release + (note == null ? "" : "：" + note), 0L, "SYSTEM", p.getPaymentNo());
        return p;
    }

    @Transactional(readOnly = true)
    public List<PaymentEvent> failedCallbacks() {
        return eventRepo.findByAppliedFalseAndCallbackFailedTrue();
    }

    @Transactional(readOnly = true)
    public PaymentOrder ofOrder(Long orderId) {
        return paymentRepo.findByOrderId(orderId).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<PaymentEvent> events(Long paymentId) {
        return eventRepo.findByPaymentIdOrderByIdAsc(paymentId);
    }

    private PaymentStatus successStatus(PaymentType t) {
        return switch (t) {
            case PREAUTH -> PaymentStatus.PREAUTHED;
            case CAPTURE -> PaymentStatus.CAPTURED;
            case REFUND -> PaymentStatus.REFUNDED;
            case COMPENSATION -> PaymentStatus.COMPENSATED;
        };
    }

    private void writeTx(Long cardId, Long orderId, Long paymentId, Long compensationId,
                         BigDecimal amount, String type, String note, String key) {
        if (key != null && txRepo.existsByIdempotencyKey(key)) return; // 双重幂等保险
        MemberTransaction tx = new MemberTransaction();
        tx.setCardId(cardId);
        tx.setOrderId(orderId);
        tx.setPaymentId(paymentId);
        tx.setCompensationId(compensationId);
        tx.setAmount(amount);
        tx.setType(type);
        tx.setNote(note);
        tx.setIdempotencyKey(key);
        txRepo.save(tx);
    }

    private String keyOr(String key, String fallback) {
        return key == null || key.isBlank() ? fallback : key;
    }
}
