package com.citylaundry.web;

import com.citylaundry.dto.ApiDtos.QcPassRequest;
import com.citylaundry.dto.ApiDtos.RiderPickupRequest;
import com.citylaundry.model.*;
import com.citylaundry.model.Enums.Role;
import com.citylaundry.repo.LaundryOrderRepo;
import com.citylaundry.security.AuthService;
import com.citylaundry.service.StoreQcService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 取件环节：骑手确认取件（建立取洗档案+预授权）、送达门店；
 * 门店逐件收件质检通过。
 */
@RestController
@RequestMapping("/api/pickup-flow")
public class PickupFlowController {
    private final AuthService auth;
    private final StoreQcService qcService;
    private final LaundryOrderRepo orderRepo;

    public PickupFlowController(AuthService auth, StoreQcService qcService, LaundryOrderRepo orderRepo) {
        this.auth = auth;
        this.qcService = qcService;
        this.orderRepo = orderRepo;
    }

    /** 骑手确认取件：建立取件记录（时间/位置/客户签字）并触发预授权 */
    @PostMapping("/orders/{orderId}/confirm")
    public PickupRecord confirm(@RequestHeader("X-Auth-Token") String token,
                                @PathVariable Long orderId,
                                @RequestBody RiderPickupRequest req) {
        UserAccount rider = auth.requireRole(token, Role.PICKUP);
        return qcService.confirmPickup(rider, orderId, req.location(), req.customerSign(), req.note());
    }

    /** 骑手送达门店，逐件进入收件质检 */
    @PostMapping("/orders/{orderId}/arrive-store")
    public List<GarmentItem> arrive(@RequestHeader("X-Auth-Token") String token,
                                    @PathVariable Long orderId) {
        UserAccount rider = auth.requireRole(token, Role.PICKUP);
        return qcService.arriveStore(rider, orderId);
    }

    /** 门店质检通过（单件） */
    @PostMapping("/items/{itemId}/qc-pass")
    public GarmentItem pass(@RequestHeader("X-Auth-Token") String token,
                            @PathVariable Long itemId,
                            @RequestBody QcPassRequest req) {
        UserAccount u = auth.requireRole(token, Role.STORE_MANAGER, Role.FACTORY, Role.CS);
        return qcService.passQc(u, itemId, req.observedMaterial(),
                req.observedAccessories(), req.conclusion());
    }

    /** 订单的取件记录 */
    @GetMapping("/orders/{orderId}/pickups")
    public List<PickupRecord> pickups(@RequestHeader("X-Auth-Token") String token,
                                      @PathVariable Long orderId) {
        auth.require(token);
        return qcService.pickupsOf(orderId);
    }

    /** 订单的门店质检记录 */
    @GetMapping("/orders/{orderId}/qc")
    public List<StoreQcRecord> qcs(@RequestHeader("X-Auth-Token") String token,
                                   @PathVariable Long orderId) {
        auth.require(token);
        return qcService.qcOfOrder(orderId);
    }
}
