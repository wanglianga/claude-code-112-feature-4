package com.citylaundry.web;

import com.citylaundry.dto.ApiDtos.ClaimRequest;
import com.citylaundry.dto.ApiDtos.CreateOrderRequest;
import com.citylaundry.model.*;
import com.citylaundry.model.Enums.Role;
import com.citylaundry.security.AuthService;
import com.citylaundry.service.*;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 顾客端：下单、确认收衣、我的订单、售后申诉 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final AuthService auth;
    private final OrderService orderService;
    private final PickupService pickupService;
    private final DeliveryService deliveryService;
    private final TicketService ticketService;
    private final CompensationService compensationService;

    public OrderController(AuthService auth, OrderService orderService, PickupService pickupService,
                           DeliveryService deliveryService, TicketService ticketService,
                           CompensationService compensationService) {
        this.auth = auth;
        this.orderService = orderService;
        this.pickupService = pickupService;
        this.deliveryService = deliveryService;
        this.ticketService = ticketService;
        this.compensationService = compensationService;
    }

    @PostMapping
    public LaundryOrder create(@RequestHeader("X-Auth-Token") String token,
                               @RequestBody CreateOrderRequest req) {
        UserAccount customer = auth.requireRole(token, Role.CUSTOMER);
        return orderService.createOrder(customer, req);
    }

    @GetMapping("/mine")
    public List<LaundryOrder> mine(@RequestHeader("X-Auth-Token") String token) {
        UserAccount customer = auth.requireRole(token, Role.CUSTOMER);
        return orderService.myOrders(customer);
    }

    /** 订单详情：含每件衣物的照片/工艺/工单/赔付（逐件独立） */
    @GetMapping("/{orderId}")
    public Map<String, Object> detail(@RequestHeader("X-Auth-Token") String token,
                                      @PathVariable Long orderId) {
        UserAccount u = auth.require(token);
        LaundryOrder order = orderService.getOrder(orderId);
        if (u.getRole() == Role.CUSTOMER && !order.getCustomerId().equals(u.getId())) {
            throw com.citylaundry.common.ApiException.forbidden("只能查看自己的订单");
        }
        List<Map<String, Object>> items = orderService.itemsOf(orderId).stream().map(item -> {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("item", item);
            m.put("photos", pickupService.photosOf(item.getId()));
            m.put("tickets", ticketService.ticketsOfItem(item.getId()));
            m.put("compensations", compensationService.casesOfItem(item.getId()));
            return m;
        }).toList();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("order", order);
        resp.put("items", items);
        resp.put("bill", deliveryService.billOf(orderId));
        return resp;
    }

    /** 顾客确认收衣照片 → 进入洗护工厂 */
    @PostMapping("/{orderId}/confirm-pickup")
    public LaundryOrder confirmPickup(@RequestHeader("X-Auth-Token") String token,
                                      @PathVariable Long orderId) {
        UserAccount customer = auth.requireRole(token, Role.CUSTOMER);
        return orderService.confirmPickup(customer, orderId);
    }

    /** 售后申诉（质疑新破损） */
    @PostMapping("/items/{itemId}/claim")
    public ExceptionTicket claim(@RequestHeader("X-Auth-Token") String token,
                                 @PathVariable Long itemId,
                                 @RequestBody ClaimRequest req) {
        UserAccount customer = auth.requireRole(token, Role.CUSTOMER);
        return deliveryService.claim(customer, itemId, req.description());
    }
}
