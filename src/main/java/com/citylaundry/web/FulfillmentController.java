package com.citylaundry.web;

import com.citylaundry.dto.ApiDtos.ReclaimAssignRequest;
import com.citylaundry.dto.ApiDtos.ReclaimCompleteRequest;
import com.citylaundry.model.Enums.ReclaimStatus;
import com.citylaundry.model.Enums.Role;
import com.citylaundry.model.GarmentItem;
import com.citylaundry.model.RiderReclaim;
import com.citylaundry.security.AuthService;
import com.citylaundry.service.FulfillmentService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 履约：拒绝承接后的骑手重派/回收、退回客户与原路退款、站点产能视图 */
@RestController
@RequestMapping("/api/fulfillment")
public class FulfillmentController {
    private final AuthService auth;
    private final FulfillmentService fulfillmentService;

    public FulfillmentController(AuthService auth, FulfillmentService fulfillmentService) {
        this.auth = auth;
        this.fulfillmentService = fulfillmentService;
    }

    /** 待处理回收任务（可按状态过滤） */
    @GetMapping("/reclaims/tasks")
    public List<RiderReclaim> tasks(@RequestHeader("X-Auth-Token") String token,
                                    @RequestParam(required = false) String status) {
        auth.requireRole(token, Role.DELIVERY, Role.PICKUP, Role.STORE_MANAGER, Role.CS);
        ReclaimStatus s = null;
        if (status != null && !status.isBlank()) {
            try { s = ReclaimStatus.valueOf(status); }
            catch (IllegalArgumentException e) {
                throw com.citylaundry.common.ApiException.badRequest("未知回收状态: " + status);
            }
        }
        return fulfillmentService.tasks(s);
    }

    /** 重派骑手 */
    @PostMapping("/reclaims/{id}/assign")
    public RiderReclaim assign(@RequestHeader("X-Auth-Token") String token,
                               @PathVariable Long id,
                               @RequestBody(required = false) ReclaimAssignRequest req) {
        var u = auth.requireRole(token, Role.DELIVERY, Role.PICKUP, Role.STORE_MANAGER, Role.CS);
        Long riderId = req == null ? null : req.riderId();
        return fulfillmentService.assign(u, id, riderId, req == null ? null : req.note());
    }

    /** 骑手完成回收（必须留影，幂等不重复出库） */
    @PostMapping("/reclaims/{id}/complete")
    public RiderReclaim complete(@RequestHeader("X-Auth-Token") String token,
                                 @PathVariable Long id,
                                 @RequestBody ReclaimCompleteRequest req) {
        var u = auth.requireRole(token, Role.DELIVERY, Role.PICKUP, Role.STORE_MANAGER);
        return fulfillmentService.completeReclaim(u, id, req.handoverPhotoUrl(), req.note());
    }

    /** 退回客户并原路退回（幂等） */
    @PostMapping("/items/{itemId}/return")
    public GarmentItem returnItem(@RequestHeader("X-Auth-Token") String token,
                                  @PathVariable Long itemId,
                                  @RequestBody(required = false) Map<String, String> body) {
        var u = auth.requireRole(token, Role.DELIVERY, Role.PICKUP, Role.STORE_MANAGER, Role.CS, Role.FINANCE);
        String note = body == null ? null : body.get("note");
        return fulfillmentService.returnToCustomer(u, itemId, note);
    }

    /** 门店/站点产能占用视图 */
    @GetMapping("/capacity")
    public Map<String, Object> capacity(@RequestHeader("X-Auth-Token") String token,
                                        @RequestParam(required = false) Long siteId) {
        auth.requireRole(token, Role.STORE_MANAGER, Role.FACTORY, Role.CS);
        return fulfillmentService.capacity(siteId);
    }
}
