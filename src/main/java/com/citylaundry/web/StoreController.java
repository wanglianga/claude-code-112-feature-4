package com.citylaundry.web;

import com.citylaundry.dto.ApiDtos.QcMeetingRequest;
import com.citylaundry.dto.ApiDtos.StoreStrategyRequest;
import com.citylaundry.model.*;
import com.citylaundry.model.Enums.Role;
import com.citylaundry.security.AuthService;
import com.citylaundry.service.StoreService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 门店：接单策略（高风险衣物）、质检会议（赔付复盘） */
@RestController
@RequestMapping("/api/stores")
public class StoreController {
    private final AuthService auth;
    private final StoreService storeService;

    public StoreController(AuthService auth, StoreService storeService) {
        this.auth = auth;
        this.storeService = storeService;
    }

    @GetMapping
    public List<Store> list(@RequestHeader("X-Auth-Token") String token) {
        auth.require(token);
        return storeService.stores();
    }

    /** 门店经理调整高风险接单策略 */
    @PutMapping("/{storeId}/strategy")
    public Store updateStrategy(@RequestHeader("X-Auth-Token") String token,
                                @PathVariable Long storeId,
                                @RequestBody StoreStrategyRequest req) {
        auth.requireRole(token, Role.STORE_MANAGER);
        return storeService.updateStrategy(storeId,
                req.acceptHighRisk() == null || req.acceptHighRisk(),
                req.maxHighRiskPerDay() == null ? 10 : req.maxHighRiskPerDay());
    }

    /** 待复盘赔付案 */
    @GetMapping("/qc/pending-cases")
    public List<CompensationCase> pendingCases(@RequestHeader("X-Auth-Token") String token) {
        auth.requireRole(token, Role.STORE_MANAGER, Role.CS);
        return storeService.pendingReviewCases();
    }

    /** 召开质检会议：赔付复盘进入会议 */
    @PostMapping("/qc/meetings")
    public QcMeeting holdMeeting(@RequestHeader("X-Auth-Token") String token,
                                 @RequestBody QcMeetingRequest req) {
        auth.requireRole(token, Role.STORE_MANAGER);
        return storeService.holdQcMeeting(req.title(), req.storeId(), req.heldAt(),
                req.conclusion(), req.caseIds());
    }

    @GetMapping("/{storeId}/qc/meetings")
    public List<QcMeeting> meetings(@RequestHeader("X-Auth-Token") String token,
                                    @PathVariable Long storeId) {
        auth.requireRole(token, Role.STORE_MANAGER, Role.CS);
        return storeService.meetingsOf(storeId);
    }
}
