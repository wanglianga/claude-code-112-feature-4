package com.citylaundry.web;

import com.citylaundry.common.ApiException;
import com.citylaundry.dto.ApiDtos.PaymentCallbackRequest;
import com.citylaundry.model.*;
import com.citylaundry.model.Enums.Role;
import com.citylaundry.repo.GarmentItemRepo;
import com.citylaundry.repo.LaundryOrderRepo;
import com.citylaundry.security.AuthService;
import com.citylaundry.service.*;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运营/关单工作台：关单前校验与自动结算、缺项人工队列、
 * 客户超时转人工、按件请款渠道回调（含衣物已洗护但回调失败的重试）。
 */
@RestController
@RequestMapping("/api/ops")
public class OpsController {
    private final AuthService auth;
    private final CloseoutService closeout;
    private final DecisionService decision;
    private final ExceptionService exceptionService;
    private final PaymentService payment;
    private final PricingService pricing;
    private final GarmentItemRepo itemRepo;
    private final LaundryOrderRepo orderRepo;

    public OpsController(AuthService auth, CloseoutService closeout, DecisionService decision,
                         ExceptionService exceptionService, PaymentService payment,
                         PricingService pricing, GarmentItemRepo itemRepo,
                         LaundryOrderRepo orderRepo) {
        this.auth = auth;
        this.closeout = closeout;
        this.decision = decision;
        this.exceptionService = exceptionService;
        this.payment = payment;
        this.pricing = pricing;
        this.itemRepo = itemRepo;
        this.orderRepo = orderRepo;
    }

    /** 关单：校验证据/客户确认/质检/包装/取回，缺项阻断自动结算转人工 */
    @PostMapping("/orders/{orderId}/closeout")
    public Map<String, Object> closeout(@RequestHeader("X-Auth-Token") String token,
                                        @PathVariable Long orderId) {
        UserAccount u = auth.requireRole(token, Role.CS, Role.STORE_MANAGER, Role.FINANCE);
        return closeout.closeout(orderId, u);
    }

    /** 仅查看关单缺项，不改状态 */
    @GetMapping("/orders/{orderId}/closeout-blocks")
    public List<ManualSettlementBlock> blocks(@RequestHeader("X-Auth-Token") String token,
                                              @PathVariable Long orderId) {
        auth.require(token);
        return closeout.blocksOf(orderId);
    }

    /** 全量人工阻断队列 */
    @GetMapping("/closeout-blocks")
    public List<ManualSettlementBlock> allBlocks(@RequestHeader("X-Auth-Token") String token) {
        auth.requireRole(token, Role.CS, Role.STORE_MANAGER, Role.FINANCE);
        return closeout.openBlocks();
    }

    /** 客服补齐缺项（全部补齐后自动重跑结算） */
    @PostMapping("/closeout-blocks/{blockId}/resolve")
    public ManualSettlementBlock resolveBlock(@RequestHeader("X-Auth-Token") String token,
                                              @PathVariable Long blockId,
                                              @RequestBody Map<String, String> body) {
        UserAccount u = auth.requireRole(token, Role.CS, Role.STORE_MANAGER);
        return closeout.resolveBlock(u, blockId, body.get("resolution"));
    }

    /** 扫描客户超时未决策异常并转人工 */
    @PostMapping("/exceptions/timeout-sweep")
    public Map<String, Object> timeoutSweep(@RequestHeader("X-Auth-Token") String token) {
        auth.requireRole(token, Role.CS, Role.STORE_MANAGER);
        int n = 0;
        for (GarmentException ex : exceptionService.overdue()) {
            decision.timeoutToManual(ex);
            n++;
        }
        return Map.of("movedToManual", n);
    }

    /**
     * 按件请款渠道回调（演示资金回调与洗护解耦）：
     * success=false 时衣物可以已在洗护中，资金不动并进入重试；
     * 用同一 idempotencyKey 重放成功只扣一次款。
     */
    @PostMapping("/orders/{orderId}/items/{itemId}/capture-callback")
    public PaymentEvent captureCallback(@RequestHeader("X-Auth-Token") String token,
                                        @PathVariable Long orderId,
                                        @PathVariable Long itemId,
                                        @RequestBody PaymentCallbackRequest req) {
        auth.requireRole(token, Role.FINANCE, Role.CS, Role.STORE_MANAGER, Role.FACTORY);
        GarmentItem item = itemRepo.findById(itemId)
                .orElseThrow(() -> ApiException.notFound("衣物不存在"));
        if (!item.getOrderId().equals(orderId)) throw ApiException.badRequest("衣物与订单不匹配");
        BigDecimal amount = req.amount() != null ? req.amount()
                : (item.getFinalPrice() != null ? item.getFinalPrice() : pricing.basePriceOf(item));
        boolean success = req.success() == null || req.success();
        return payment.captureItemCallback(orderId, itemId, amount,
                req.idempotencyKey(), success, success ? null : req.error());
    }
}
