package com.citylaundry.service;

import com.citylaundry.common.ApiException;
import com.citylaundry.model.*;
import com.citylaundry.model.Enums.*;
import com.citylaundry.repo.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 交付：合并洗护记录、复拍照片、支付扣款（逐件支付单幂等扣款+渠道回调）、
 * 赔付与售后时限。拒绝承接已退回的衣物不计费、不重复出库。
 */
@Service
public class DeliveryService {
    private final LaundryOrderRepo orderRepo;
    private final GarmentItemRepo itemRepo;
    private final ProcessRecordRepo processRepo;
    private final GarmentPhotoRepo photoRepo;
    private final TransferRecordRepo transferRepo;
    private final MemberCardRepo cardRepo;
    private final MemberTransactionRepo txRepo;
    private final AfterSalesRecordRepo afterSalesRepo;
    private final CompensationCaseRepo caseRepo;
    private final PaymentRepo paymentRepo;
    private final PaymentService paymentService;
    private final GarmentStateMachine sm;
    private final TicketService ticketService;

    @Value("${app.after-sales-days:7}")
    private int afterSalesDays;

    public DeliveryService(LaundryOrderRepo orderRepo, GarmentItemRepo itemRepo,
                           ProcessRecordRepo processRepo, GarmentPhotoRepo photoRepo,
                           TransferRecordRepo transferRepo, MemberCardRepo cardRepo,
                           MemberTransactionRepo txRepo, AfterSalesRecordRepo afterSalesRepo,
                           CompensationCaseRepo caseRepo, PaymentRepo paymentRepo,
                           PaymentService paymentService, GarmentStateMachine sm,
                           TicketService ticketService) {
        this.orderRepo = orderRepo;
        this.itemRepo = itemRepo;
        this.processRepo = processRepo;
        this.photoRepo = photoRepo;
        this.transferRepo = transferRepo;
        this.cardRepo = cardRepo;
        this.txRepo = txRepo;
        this.afterSalesRepo = afterSalesRepo;
        this.caseRepo = caseRepo;
        this.paymentRepo = paymentRepo;
        this.paymentService = paymentService;
        this.sm = sm;
        this.ticketService = ticketService;
    }

    /** 单件最终应收 = 基础工艺价 + 客户已确认追加价（拒洗/退回件为 0） */
    public static BigDecimal itemCharge(GarmentItem item) {
        if (item.getStatus() == GarmentStatus.REJECTED || item.getStatus() == GarmentStatus.RETURNED) {
            return BigDecimal.ZERO;
        }
        BigDecimal base = item.getBasePrice() != null && item.getBasePrice().compareTo(BigDecimal.ZERO) > 0
                ? item.getBasePrice() : PricingService.estimatePrice(item);
        BigDecimal extra = item.getAgreedExtra() == null ? BigDecimal.ZERO : item.getAgreedExtra();
        return base.add(extra);
    }

    /** 订单账单预览（含追加报价；团体工服仅对服务费 9 折，配送费不折） */
    public Map<String, Object> billOf(Long orderId) {
        LaundryOrder order = orderRepo.findById(orderId).orElseThrow(() -> ApiException.notFound("订单不存在"));
        List<GarmentItem> items = itemRepo.findByOrderIdOrderByIdAsc(orderId);
        List<Map<String, Object>> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (GarmentItem item : items) {
            BigDecimal base = itemCharge(item).subtract(item.getAgreedExtra() == null ? BigDecimal.ZERO : item.getAgreedExtra());
            BigDecimal extra = item.getAgreedExtra() == null ? BigDecimal.ZERO : item.getAgreedExtra();
            BigDecimal price = base.add(extra);
            subtotal = subtotal.add(price);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("itemCode", item.getItemCode());
            line.put("type", item.getType());
            line.put("processType", item.getProcessType());
            line.put("basePrice", base);
            line.put("agreedExtra", extra);
            line.put("status", item.getStatus());
            line.put("price", price);
            lines.add(line);
        }
        BigDecimal discount = BigDecimal.ZERO;
        if (order.getType() == OrderType.GROUP_WORKWEAR) {
            discount = subtotal.multiply(new BigDecimal("0.10")).setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal deliveryFee = order.isSelfPickup() ? BigDecimal.ZERO : PricingService.DELIVERY_FEE;
        BigDecimal extraTotal = items.stream()
                .map(i -> i.getAgreedExtra() == null ? BigDecimal.ZERO : i.getAgreedExtra())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = subtotal.subtract(discount).add(deliveryFee);

        Map<String, Object> bill = new LinkedHashMap<>();
        bill.put("orderNo", order.getOrderNo());
        bill.put("lines", lines);
        bill.put("subtotal", subtotal);
        bill.put("groupDiscount", discount);
        bill.put("extraAmount", extraTotal);
        bill.put("deliveryFee", deliveryFee);
        bill.put("selfPickup", order.isSelfPickup());
        bill.put("total", total);
        return bill;
    }

    /**
     * 交付订单：逐件幂等扣款（预授权→扣款→模拟渠道回调成功），交付留影，生成逐件售后记录。
     * simulateFailItemId 用于演示「支付回调失败」：该件扣款回调失败、衣物仍完成交付等待重试。
     */
    @Transactional
    public Map<String, Object> deliver(UserAccount actor, Long orderId, String deliveryPhotoUrl) {
        return deliver(actor, orderId, deliveryPhotoUrl, null);
    }

    @Transactional
    public Map<String, Object> deliver(UserAccount actor, Long orderId, String deliveryPhotoUrl,
                                       Long simulateFailItemId) {
        LaundryOrder order = orderRepo.findById(orderId).orElseThrow(() -> ApiException.notFound("订单不存在"));
        if (order.getStatus() != OrderStatus.READY_FOR_DELIVERY
                && order.getStatus() != OrderStatus.PARTIALLY_RETURNED) {
            throw ApiException.conflict("订单未就绪，不能交付: " + order.getStatus());
        }
        List<GarmentItem> items = itemRepo.findByOrderIdOrderByIdAsc(orderId);
        for (GarmentItem item : items) {
            if (item.getStatus() != GarmentStatus.READY_FOR_DELIVERY
                    && item.getStatus() != GarmentStatus.REJECTED
                    && item.getStatus() != GarmentStatus.RETURNED) {
                throw ApiException.conflict("衣物 " + item.getItemCode() + " 未就绪: " + item.getStatus());
            }
        }

        MemberCard card = cardRepo.findById(order.getMemberCardId()).orElseThrow();
        BigDecimal charged = BigDecimal.ZERO;
        List<Map<String, Object>> payResults = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> handovers = new ArrayList<>();

        for (GarmentItem item : items) {
            if (item.getStatus() == GarmentStatus.RETURNED) {
                continue; // 已退回客户，不计费不交付留影
            }

            if (item.getStatus() != GarmentStatus.REJECTED) {
                List<Payment> pays = captureItemPayments(item, simulateFailItemId);
                for (Payment pay : pays) {
                    Map<String, Object> pr = new LinkedHashMap<>();
                    pr.put("itemCode", item.getItemCode());
                    pr.put("paymentNo", pay.getPaymentNo());
                    pr.put("bizRef", pay.getBizRef());
                    pr.put("status", pay.getStatus());
                    pr.put("capturedAmount", pay.getCapturedAmount());
                    pr.put("failReason", pay.getFailReason());
                    payResults.add(pr);
                    if (pay.getStatus() == PaymentStatus.CAPTURED) charged = charged.add(pay.getCapturedAmount());
                }
            }

            if (item.getStatus() != GarmentStatus.REJECTED) {
                item = sm.transition(item.getId(), GarmentStatus.DELIVERED, "DELIVER",
                        "ORDER", order.getId(), actor.getId(), "交付客户");
            }

            if (deliveryPhotoUrl != null && !deliveryPhotoUrl.isBlank()) {
                GarmentPhoto photo = new GarmentPhoto();
                photo.setItemId(item.getId());
                photo.setStage(PhotoStage.DELIVERY);
                photo.setUrl(deliveryPhotoUrl);
                photo.setUploadedBy(actor.getId());
                photoRepo.save(photo);
            }

            String summary = buildHandoverSummary(item);
            AfterSalesRecord as = new AfterSalesRecord();
            as.setItemId(item.getId());
            as.setOrderId(order.getId());
            as.setDeliveredAt(now);
            as.setDeadline(now.plusDays(afterSalesDays));
            as.setStatus(AfterSalesStatus.ACTIVE);
            as.setHandoverSummary(summary);
            afterSalesRepo.save(as);

            Map<String, Object> h = new LinkedHashMap<>();
            h.put("itemCode", item.getItemCode());
            h.put("afterSalesDeadline", as.getDeadline());
            h.put("handoverSummary", summary);
            handovers.add(h);
        }

        // 团体工服 9 折：把折扣作为幂等调减返还到卡；配送费作为订单级幂等扣款
        Map<String, Object> bill = billOf(orderId);
        BigDecimal discount = (BigDecimal) bill.get("groupDiscount");
        BigDecimal deliveryFee = (BigDecimal) bill.get("deliveryFee");
        if (discount.compareTo(BigDecimal.ZERO) > 0) {
            cardAdjustment(card, order, discount, "GROUP_DISCOUNT",
                    "discount-" + order.getId(), "团体工服 9 折调减");
            charged = charged.subtract(discount);
        }
        if (deliveryFee.compareTo(BigDecimal.ZERO) > 0) {
            if (card.getBalance().compareTo(deliveryFee) < 0) {
                throw ApiException.conflict("会员卡余额不足，无法扣配送费 ¥" + deliveryFee);
            }
            cardAdjustment(card, order, deliveryFee.negate(), "PAYMENT",
                    "delivery-" + order.getId(), "订单配送费");
            charged = charged.add(deliveryFee);
        }

        order.setTotalAmount(charged.max(BigDecimal.ZERO));
        order.setStatus(OrderStatus.DELIVERED);
        orderRepo.save(order);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderNo", order.getOrderNo());
        result.put("status", order.getStatus());
        result.put("charged", charged.max(BigDecimal.ZERO));
        result.put("cardBalance", card.getBalance());
        result.put("frozenBalance", card.getFrozenBalance());
        result.put("payments", payResults);
        result.put("bill", bill);
        result.put("handovers", handovers);
        return result;
    }

    /** 会员卡订单级直接调整（配送费/折扣），幂等键防止重复出入账 */
    private void cardAdjustment(MemberCard card, LaundryOrder order, BigDecimal amount,
                                String type, String key, String note) {
        if (txRepo.findByIdempotencyKey(key).isPresent()) return;
        card.setBalance(card.getBalance().add(amount));
        cardRepo.save(card);
        MemberTransaction tx = new MemberTransaction();
        tx.setCardId(card.getId());
        tx.setOrderId(order.getId());
        tx.setAmount(amount);
        tx.setType(type);
        tx.setIdempotencyKey(key);
        tx.setNote(note);
        txRepo.save(tx);
    }

    /**
     * 逐支付单扣款（幂等）：该件的基础预授权按最终基础工艺价部分扣款（预授权高于实际价时差额释放），
     * 追加预授权全额扣款；回调流水号按支付单唯一，重复交付/重试不重复扣款。
     * simulateFailItemId 指定的衣物回调失败，冻结保留、等待重试，关单校验将拦截结算。
     */
    private List<Payment> captureItemPayments(GarmentItem item, Long simulateFailItemId) {
        boolean simulateFail = simulateFailItemId != null && simulateFailItemId.equals(item.getId());
        BigDecimal finalBase = item.getBasePrice() == null ? BigDecimal.ZERO : item.getBasePrice();
        List<Payment> out = new ArrayList<>();

        for (Payment p : paymentRepo.findByItemIdOrderByCreatedAtAsc(item.getId())) {
            if (p.getStatus() == PaymentStatus.CAPTURED || p.getStatus() == PaymentStatus.REFUNDED
                    || p.getStatus() == PaymentStatus.RELEASED) {
                if (p.getStatus() == PaymentStatus.CAPTURED) out.add(p);
                continue;
            }
            if (p.getStatus() != PaymentStatus.AUTHORIZED && p.getStatus() != PaymentStatus.PENDING
                    && p.getStatus() != PaymentStatus.FAILED) {
                continue;
            }
            boolean base = p.getBizRef() != null && p.getBizRef().startsWith("BASE:");
            BigDecimal want = base ? finalBase.min(p.getAmount()) : p.getAmount();
            if (p.getStatus() == PaymentStatus.AUTHORIZED || p.getStatus() == PaymentStatus.PENDING) {
                paymentService.requestCapture(p.getId(), "capture-pay-" + p.getId(), want);
            }
            // FAILED 单（如洗护中回调失败）在交付时用新流水号重试；衣物流程从未回退
            String callbackId = p.getStatus() == PaymentStatus.FAILED
                    ? "CB-PAY-" + p.getId() + "-2" : "CB-PAY-" + p.getId() + "-1";
            Payment after = paymentService.handleCallback(p.getId(), callbackId, !simulateFail,
                    simulateFail ? "模拟支付渠道回调失败" : null);
            out.add(after);
        }
        return out;
    }

    /** 交付合并摘要：工艺记录 + 复拍照片 + 多站点交接 + 赔付记录 */
    private String buildHandoverSummary(GarmentItem item) {
        StringBuilder sb = new StringBuilder();
        sb.append("衣物[").append(item.getItemCode()).append("] ");
        List<ProcessRecord> processes = processRepo.findByItemIdOrderByStartedAtAsc(item.getId());
        for (ProcessRecord p : processes) {
            sb.append("工艺:").append(p.getProcessType())
              .append("(站点").append(p.getSiteId()).append(") ");
        }
        List<GarmentPhoto> reshoots = photoRepo.findByItemIdAndStage(item.getId(), PhotoStage.FACTORY_RESHOOT);
        sb.append("复拍照片:").append(reshoots.size()).append("张 ");
        List<TransferRecord> transfers = transferRepo.findByItemIdOrderByCreatedAtAsc(item.getId());
        sb.append("交接:").append(transfers.size()).append("次 ");
        List<CompensationCase> cases = caseRepo.findByItemId(item.getId());
        for (CompensationCase c : cases) {
            sb.append("赔付:").append(c.getCaseNo()).append("/¥").append(c.getAmount())
              .append("/").append(c.getStatus()).append(" ");
        }
        return sb.toString().trim();
    }

    /** 售后申诉（顾客质疑新破损）：校验售后时限 → 生成 DAMAGE_DISPUTE 工单 */
    @Transactional
    public ExceptionTicket claim(UserAccount customer, Long itemId, String description) {
        GarmentItem item = itemRepo.findById(itemId).orElseThrow(() -> ApiException.notFound("衣物不存在"));
        LaundryOrder order = orderRepo.findById(item.getOrderId()).orElseThrow();
        if (!order.getCustomerId().equals(customer.getId())) {
            throw ApiException.forbidden("只能对自己的衣物申诉");
        }
        AfterSalesRecord as = afterSalesRepo.findByItemId(itemId)
                .orElseThrow(() -> ApiException.conflict("该衣物没有售后记录（可能未交付）"));
        if (as.getStatus() == AfterSalesStatus.EXPIRED || as.getDeadline().isBefore(LocalDateTime.now())) {
            throw ApiException.conflict("售后期限已过(" + as.getDeadline() + ")，无法申诉");
        }
        as.setStatus(AfterSalesStatus.CLAIMED);
        afterSalesRepo.save(as);
        return ticketService.raise(customer, itemId, ExceptionType.DAMAGE_DISPUTE, description, null, null);
    }

    /** 售后到期扫描（演示用）：过期记录置 EXPIRED，衣物关闭 */
    @Transactional
    public Map<String, Object> sweepExpiredAfterSales() {
        int expired = 0;
        LocalDateTime now = LocalDateTime.now();
        for (AfterSalesRecord as : afterSalesRepo.findAll()) {
            if (as.getStatus() == AfterSalesStatus.ACTIVE && as.getDeadline().isBefore(now)) {
                as.setStatus(AfterSalesStatus.EXPIRED);
                afterSalesRepo.save(as);
                GarmentItem item = itemRepo.findById(as.getItemId()).orElse(null);
                if (item != null && item.getStatus() == GarmentStatus.DELIVERED) {
                    sm.transition(item.getId(), GarmentStatus.CLOSED, "AFTER_SALES_EXPIRED",
                            "AFTER_SALES", as.getId(), 0L, "售后到期关闭");
                    expired++;
                }
            }
        }
        return Map.of("expired", expired);
    }
}
