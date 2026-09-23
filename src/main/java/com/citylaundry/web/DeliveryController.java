package com.citylaundry.web;

import com.citylaundry.model.*;
import com.citylaundry.model.Enums.*;
import com.citylaundry.repo.AfterSalesRecordRepo;
import com.citylaundry.repo.LaundryOrderRepo;
import com.citylaundry.security.AuthService;
import com.citylaundry.service.DeliveryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 配送/交付：就绪订单、交付执行（合并洗护+复拍+扣费+赔付+售后时限） */
@RestController
@RequestMapping("/api/delivery")
public class DeliveryController {
    private final AuthService auth;
    private final DeliveryService deliveryService;
    private final LaundryOrderRepo orderRepo;
    private final AfterSalesRecordRepo afterSalesRepo;

    public DeliveryController(AuthService auth, DeliveryService deliveryService,
                              LaundryOrderRepo orderRepo, AfterSalesRecordRepo afterSalesRepo) {
        this.auth = auth;
        this.deliveryService = deliveryService;
        this.orderRepo = orderRepo;
        this.afterSalesRepo = afterSalesRepo;
    }

    /** 待交付订单 */
    @GetMapping("/tasks")
    public List<LaundryOrder> tasks(@RequestHeader("X-Auth-Token") String token) {
        auth.requireRole(token, Role.DELIVERY, Role.STORE_MANAGER);
        return orderRepo.findByStatusOrderByCreatedAtAsc(OrderStatus.READY_FOR_DELIVERY);
    }

    /** 交付订单：会员卡扣费 + 逐件售后记录生成 */
    @PostMapping("/orders/{orderId}/deliver")
    public Map<String, Object> deliver(@RequestHeader("X-Auth-Token") String token,
                                       @PathVariable Long orderId,
                                       @RequestBody(required = false) Map<String, String> body) {
        UserAccount u = auth.requireRole(token, Role.DELIVERY, Role.STORE_MANAGER);
        String photoUrl = body == null ? null : body.get("deliveryPhotoUrl");
        return deliveryService.deliver(u, orderId, photoUrl);
    }

    /** 账单预览 */
    @GetMapping("/orders/{orderId}/bill")
    public Map<String, Object> bill(@RequestHeader("X-Auth-Token") String token,
                                    @PathVariable Long orderId) {
        auth.require(token);
        return deliveryService.billOf(orderId);
    }

    /** 订单售后记录（售后时限） */
    @GetMapping("/orders/{orderId}/after-sales")
    public List<AfterSalesRecord> afterSales(@RequestHeader("X-Auth-Token") String token,
                                             @PathVariable Long orderId) {
        auth.require(token);
        return afterSalesRepo.findByOrderId(orderId);
    }

    /** 售后到期扫描（演示用） */
    @PostMapping("/after-sales/sweep")
    public Map<String, Object> sweep(@RequestHeader("X-Auth-Token") String token) {
        auth.requireRole(token, Role.STORE_MANAGER, Role.CS);
        return deliveryService.sweepExpiredAfterSales();
    }
}
