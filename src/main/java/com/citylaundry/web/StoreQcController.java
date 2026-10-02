package com.citylaundry.web;

import com.citylaundry.dto.ApiDtos.FinalQcRequest;
import com.citylaundry.dto.ApiDtos.IntakeQcRequest;
import com.citylaundry.model.Enums.QcConclusion;
import com.citylaundry.model.Enums.Role;
import com.citylaundry.model.StoreQcRecord;
import com.citylaundry.security.AuthService;
import com.citylaundry.service.StoreQcService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 门店质检：入厂质检结论 + 关单前复检（包装/取回确认） */
@RestController
@RequestMapping("/api/store-qc")
public class StoreQcController {
    private final AuthService auth;
    private final StoreQcService qcService;

    public StoreQcController(AuthService auth, StoreQcService qcService) {
        this.auth = auth;
        this.qcService = qcService;
    }

    /** 入厂质检结论：PASS / ANOMALY / BASIC_ONLY / REJECT */
    @PostMapping("/items/{itemId}/intake")
    public StoreQcRecord intake(@RequestHeader("X-Auth-Token") String token,
                                @PathVariable Long itemId,
                                @RequestBody IntakeQcRequest req) {
        var u = auth.requireRole(token, Role.FACTORY, Role.STORE_MANAGER, Role.CS);
        QcConclusion c;
        try {
            c = QcConclusion.valueOf(req.conclusion());
        } catch (Exception e) {
            throw com.citylaundry.common.ApiException.badRequest("未知质检结论: " + req.conclusion());
        }
        return qcService.recordIntake(u, itemId, c, req.materialCheck(), req.accessoryCheck(), req.note());
    }

    /** 关单前复检：确认包装照片与交付/取回记录齐全 */
    @PostMapping("/items/{itemId}/final")
    public StoreQcRecord finalQc(@RequestHeader("X-Auth-Token") String token,
                                 @PathVariable Long itemId,
                                 @RequestBody FinalQcRequest req) {
        var u = auth.requireRole(token, Role.STORE_MANAGER, Role.FACTORY, Role.CS, Role.DELIVERY);
        return qcService.recordFinal(u, itemId,
                Boolean.TRUE.equals(req.packagingConfirmed()),
                Boolean.TRUE.equals(req.handoverConfirmed()), req.note());
    }

    @GetMapping("/items/{itemId}")
    public List<StoreQcRecord> ofItem(@RequestHeader("X-Auth-Token") String token,
                                      @PathVariable Long itemId) {
        auth.require(token);
        return qcService.qcOfItem(itemId);
    }
}
