package com.citylaundry.web;

import com.citylaundry.dto.ApiDtos.PaymentCallbackRequest;
import com.citylaundry.model.Enums.PaymentType;
import com.citylaundry.model.PaymentEvent;
import com.citylaundry.model.PaymentOrder;
import com.citylaundry.model.Enums.Role;
import com.citylaundry.security.AuthService;
import com.citylaundry.service.PaymentService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 支付：查询订单支付单与回调事件；接收渠道回调（幂等）；
 * 衣物已进入洗护但回调失败时可安全重试，不产生第二次扣款。
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private final AuthService auth;
    private final PaymentService service;

    public PaymentController(AuthService auth, PaymentService service) {
        this.auth = auth;
        this.service = service;
    }

    /** 订单支付单（含预授权/请款/退款金额与状态）+ 事件流水 */
    @GetMapping("/orders/{orderId}")
    public Map<String, Object> ofOrder(@RequestHeader("X-Auth-Token") String token,
                                       @PathVariable Long orderId) {
        auth.require(token);
        PaymentOrder p = service.ofOrder(orderId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("payment", p);
        m.put("events", p == null ? List.of() : service.events(p.getId()));
        return m;
    }

    /**
     * 渠道回调（演示）。幂等键重复不重复扣款/退款；
     * success=false 模拟回调失败（资金不动，支付单 RETRYING）。
     */
    @PostMapping("/orders/{orderId}/callback")
    public PaymentEvent callback(@RequestHeader("X-Auth-Token") String token,
                                 @PathVariable Long orderId,
                                 @RequestBody PaymentCallbackRequest req) {
        auth.requireRole(token, Role.FINANCE, Role.CS, Role.STORE_MANAGER);
        PaymentOrder p = service.ofOrder(orderId);
        if (p == null) throw com.citylaundry.common.ApiException.notFound("订单无支付单");
        PaymentType type;
        try {
            type = PaymentType.valueOf(req.type() == null ? "CAPTURE" : req.type());
        } catch (Exception e) {
            throw com.citylaundry.common.ApiException.badRequest("未知支付类型: " + req.type());
        }
        return service.receiveCallback(p.getId(), type, req.idempotencyKey(),
                req.amount(), Boolean.TRUE.equals(req.success()),
                null, req.success() == null || req.success() ? null : req.error());
    }

    /** 所有回调失败待重试事件 */
    @GetMapping("/failed-callbacks")
    public List<PaymentEvent> failed(@RequestHeader("X-Auth-Token") String token) {
        auth.requireRole(token, Role.FINANCE, Role.CS);
        return service.failedCallbacks();
    }

    /** 重试失败回调（同幂等键，只应用一次） */
    @PostMapping("/events/{eventId}/retry")
    public PaymentEvent retry(@RequestHeader("X-Auth-Token") String token,
                              @PathVariable Long eventId) {
        auth.requireRole(token, Role.FINANCE, Role.CS);
        return service.retryCallback(eventId);
    }
}
