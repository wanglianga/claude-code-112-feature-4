package com.citylaundry.web;

import com.citylaundry.dto.ApiDtos.CaptureRequest;
import com.citylaundry.dto.ApiDtos.PaymentCallbackRequest;
import com.citylaundry.dto.ApiDtos.RefundRequest;
import com.citylaundry.model.Enums.Role;
import com.citylaundry.model.Payment;
import com.citylaundry.security.AuthService;
import com.citylaundry.service.PaymentService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 支付：预授权查询、发起扣款、渠道回调（可重放）、退款。
 * 全链路幂等：requestId / captureIdempotencyKey / callbackId / refund idempotencyKey。
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private final AuthService auth;
    private final PaymentService paymentService;

    public PaymentController(AuthService auth, PaymentService paymentService) {
        this.auth = auth;
        this.paymentService = paymentService;
    }

    @GetMapping("/{paymentId}")
    public Payment detail(@RequestHeader("X-Auth-Token") String token, @PathVariable Long paymentId) {
        auth.requireRole(token, Role.CS, Role.FINANCE, Role.STORE_MANAGER, Role.FACTORY, Role.DELIVERY);
        return paymentService.getPayment(paymentId);
    }

    @GetMapping("/item/{itemId}")
    public List<Payment> byItem(@RequestHeader("X-Auth-Token") String token, @PathVariable Long itemId) {
        auth.require(token);
        return paymentService.byItem(itemId);
    }

    /** 发起扣款（等待渠道回调）；重复幂等键不会重复扣款 */
    @PostMapping("/{paymentId}/capture")
    public Payment capture(@RequestHeader("X-Auth-Token") String token, @PathVariable Long paymentId,
                           @RequestBody CaptureRequest req) {
        auth.requireRole(token, Role.FINANCE, Role.DELIVERY, Role.STORE_MANAGER);
        return paymentService.requestCapture(paymentId, req.idempotencyKey());
    }

    /**
     * 支付渠道回调（模拟）。衣物已洗护但回调失败时可用相同/新 callbackId 重放：
     * 相同 callbackId 幂等返回；新流水号对 FAILED 单可重试成功，只扣一次款。
     */
    @PostMapping("/{paymentId}/callback")
    public Payment callback(@RequestHeader("X-Auth-Token") String token, @PathVariable Long paymentId,
                            @RequestBody PaymentCallbackRequest req) {
        auth.requireRole(token, Role.FINANCE, Role.DELIVERY, Role.STORE_MANAGER);
        return paymentService.handleCallback(paymentId, req.callbackId(),
                req.success() == null || req.success(), req.failReason());
    }

    /** 原路退回/释放（幂等：相同 idempotencyKey 只退一次） */
    @PostMapping("/{paymentId}/refund")
    public Payment refund(@RequestHeader("X-Auth-Token") String token, @PathVariable Long paymentId,
                          @RequestBody RefundRequest req) {
        auth.requireRole(token, Role.FINANCE, Role.CS, Role.STORE_MANAGER);
        return paymentService.refund(paymentId, req.amount(), req.idempotencyKey());
    }
}
