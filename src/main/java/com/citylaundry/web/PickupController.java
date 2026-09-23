package com.citylaundry.web;

import com.citylaundry.dto.ApiDtos.PhotoRequest;
import com.citylaundry.model.*;
import com.citylaundry.model.Enums.*;
import com.citylaundry.repo.GarmentItemRepo;
import com.citylaundry.repo.LaundryOrderRepo;
import com.citylaundry.security.AuthService;
import com.citylaundry.service.PickupService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 收衣员：待收衣任务、上门拍照 */
@RestController
@RequestMapping("/api/pickup")
public class PickupController {
    private final AuthService auth;
    private final LaundryOrderRepo orderRepo;
    private final GarmentItemRepo itemRepo;
    private final PickupService pickupService;

    public PickupController(AuthService auth, LaundryOrderRepo orderRepo,
                            GarmentItemRepo itemRepo, PickupService pickupService) {
        this.auth = auth;
        this.orderRepo = orderRepo;
        this.itemRepo = itemRepo;
        this.pickupService = pickupService;
    }

    /** 待上门收衣的订单 */
    @GetMapping("/tasks")
    public List<LaundryOrder> tasks(@RequestHeader("X-Auth-Token") String token) {
        auth.requireRole(token, Role.PICKUP);
        return orderRepo.findByStatusOrderByCreatedAtAsc(OrderStatus.CREATED);
    }

    @GetMapping("/orders/{orderId}/items")
    public List<GarmentItem> items(@RequestHeader("X-Auth-Token") String token,
                                   @PathVariable Long orderId) {
        auth.requireRole(token, Role.PICKUP, Role.FACTORY, Role.CS, Role.DELIVERY, Role.STORE_MANAGER);
        return itemRepo.findByOrderIdOrderByIdAsc(orderId);
    }

    /** 上传衣物照片：PICKUP_FRONT/PICKUP_BACK/STAIN/CARE_LABEL/ACCESSORY */
    @PostMapping("/items/{itemId}/photos")
    public GarmentPhoto upload(@RequestHeader("X-Auth-Token") String token,
                               @PathVariable Long itemId,
                               @RequestBody PhotoRequest req) {
        UserAccount u = auth.requireRole(token, Role.PICKUP);
        PhotoStage stage = parseStage(req.stage());
        return pickupService.uploadPhoto(u, itemId, stage, req.url());
    }

    static PhotoStage parseStage(String s) {
        try {
            return PhotoStage.valueOf(s);
        } catch (Exception e) {
            throw com.citylaundry.common.ApiException.badRequest("未知照片阶段: " + s);
        }
    }
}
