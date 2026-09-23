package com.citylaundry.web;

import com.citylaundry.dto.ApiDtos.*;
import com.citylaundry.model.*;
import com.citylaundry.model.Enums.Role;
import com.citylaundry.security.AuthService;
import com.citylaundry.service.CompensationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 赔付：客服逐件立案 → 财务审批 → 赔付执行（支持保险赔付） */
@RestController
@RequestMapping("/api/compensations")
public class CompensationController {
    private final AuthService auth;
    private final CompensationService compensationService;

    public CompensationController(AuthService auth, CompensationService compensationService) {
        this.auth = auth;
        this.compensationService = compensationService;
    }

    /** 客服基于异常工单对单件衣物立案 */
    @PostMapping
    public CompensationCase create(@RequestHeader("X-Auth-Token") String token,
                                   @RequestBody CreateCompensationRequest req) {
        UserAccount cs = auth.requireRole(token, Role.CS);
        return compensationService.createCase(cs, req.ticketId(), req.amount(),
                req.responsibility(), Boolean.TRUE.equals(req.insuranceClaim()),
                req.insurancePolicyNo(), req.reason());
    }

    @GetMapping
    public List<CompensationCase> list(@RequestHeader("X-Auth-Token") String token) {
        auth.requireRole(token, Role.CS, Role.FINANCE, Role.STORE_MANAGER);
        return compensationService.listAll();
    }

    @GetMapping("/{caseId}")
    public CompensationCase detail(@RequestHeader("X-Auth-Token") String token,
                                   @PathVariable Long caseId) {
        auth.requireRole(token, Role.CS, Role.FINANCE, Role.STORE_MANAGER);
        return compensationService.getCase(caseId);
    }

    /** 财务审批 */
    @PostMapping("/{caseId}/approve")
    public CompensationCase approve(@RequestHeader("X-Auth-Token") String token,
                                    @PathVariable Long caseId,
                                    @RequestBody ApproveRequest req) {
        UserAccount finance = auth.requireRole(token, Role.FINANCE);
        return compensationService.approve(finance, caseId, Boolean.TRUE.equals(req.approve()), req.reason());
    }

    /** 财务执行赔付：金额划入顾客会员卡 */
    @PostMapping("/{caseId}/pay")
    public CompensationCase pay(@RequestHeader("X-Auth-Token") String token,
                                @PathVariable Long caseId) {
        UserAccount finance = auth.requireRole(token, Role.FINANCE);
        return compensationService.pay(finance, caseId);
    }
}
