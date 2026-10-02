package com.citylaundry.web;

import com.citylaundry.dto.ApiDtos.*;
import com.citylaundry.model.*;
import com.citylaundry.model.Enums.*;
import com.citylaundry.security.AuthService;
import com.citylaundry.service.*;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 异常项 / 证据 / 多版本报价 / 客户决策 / 人工复核。
 * 骑手或门店发现材质不符、高风险污渍、配件缺失、不可去除瑕疵时在此建档，不得直接加价或继续洗护。
 */
@RestController
@RequestMapping("/api/issues")
public class IssueController {
    private final AuthService auth;
    private final AnomalyService anomalyService;

    public IssueController(AuthService auth, AnomalyService anomalyService) {
        this.auth = auth;
        this.anomalyService = anomalyService;
    }

    /** 骑手/门店登记异常项（附原始照片、拍摄时间、位置、处理建议），系统自动生成候选报价版本 */
    @PostMapping
    public AnomalyIssue raise(@RequestHeader("X-Auth-Token") String token,
                              @RequestBody CreateIssueRequest req) {
        UserAccount u = auth.requireRole(token, Role.PICKUP, Role.FACTORY, Role.STORE_MANAGER, Role.CS);
        ExceptionType type;
        try {
            type = ExceptionType.valueOf(req.type());
        } catch (Exception e) {
            throw com.citylaundry.common.ApiException.badRequest("未知异常类型: " + req.type());
        }
        return anomalyService.raiseIssue(u, req.itemId(), type, req.description(), req.severity(),
                req.suggestion(), req.actualMaterial(), req.actualBrand(),
                req.photoUrl(), req.takenAt(), req.location());
    }

    /** 多次补拍 / 骑手与门店判断不一致：新增证据（可重新生成报价版本，原判断不覆盖） */
    @PostMapping("/{issueId}/evidence")
    public IssueEvidence addEvidence(@RequestHeader("X-Auth-Token") String token,
                                     @PathVariable Long issueId,
                                     @RequestBody AddEvidenceRequest req) {
        UserAccount u = auth.requireRole(token, Role.PICKUP, Role.FACTORY, Role.STORE_MANAGER, Role.CS);
        return anomalyService.addEvidence(u, issueId, req.photoUrl(), req.takenAt(), req.location(),
                req.sourceStage(), req.disagreementNote(), req.suggestion(),
                req.reprice() == null || req.reprice());
    }

    /** 异常项详情：证据（逐轮补拍）、报价版本、客户决策、人工复核 */
    @GetMapping("/{issueId}")
    public Map<String, Object> detail(@RequestHeader("X-Auth-Token") String token,
                                      @PathVariable Long issueId) {
        auth.require(token);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("issue", anomalyService.getIssue(issueId));
        resp.put("evidence", anomalyService.evidencesOf(issueId));
        resp.put("quoteVersions", anomalyService.quotesOf(issueId));
        resp.put("decisions", anomalyService.decisionsOf(issueId));
        return resp;
    }

    @GetMapping
    public List<AnomalyIssue> list(@RequestHeader("X-Auth-Token") String token,
                                   @RequestParam(required = false) String status) {
        auth.requireRole(token, Role.CS, Role.FACTORY, Role.STORE_MANAGER, Role.FINANCE,
                Role.PICKUP, Role.DELIVERY);
        if (status == null || status.isBlank()) return anomalyService.openIssues();
        try {
            return anomalyService.openIssues().stream()
                    .filter(i -> i.getStatus().name().equals(status)).toList();
        } catch (Exception e) {
            throw com.citylaundry.common.ApiException.badRequest("未知异常状态: " + status);
        }
    }

    /** 客户在时限内确认 / 拒绝（退回或按原服务继续）/ 仅基础处理 / 申请人工复核 */
    @PostMapping("/{issueId}/decision")
    public CustomerDecision decide(@RequestHeader("X-Auth-Token") String token,
                                   @PathVariable Long issueId,
                                   @RequestBody DecisionRequest req) {
        UserAccount customer = auth.requireRole(token, Role.CUSTOMER);
        CustomerAction action;
        try {
            action = CustomerAction.valueOf(req.action());
        } catch (Exception e) {
            throw com.citylaundry.common.ApiException.badRequest("未知决策动作: " + req.action());
        }
        if (action == CustomerAction.WITHDRAW) {
            throw com.citylaundry.common.ApiException.badRequest("撤回请使用 /withdraw 接口");
        }
        return anomalyService.decide(customer, issueId, action, req.note());
    }

    /** 客户确认追加后、洗护未开始前撤回 */
    @PostMapping("/{issueId}/withdraw")
    public CustomerDecision withdraw(@RequestHeader("X-Auth-Token") String token,
                                     @PathVariable Long issueId,
                                     @RequestBody(required = false) DecisionRequest req) {
        UserAccount customer = auth.requireRole(token, Role.CUSTOMER);
        return anomalyService.withdraw(customer, issueId, req == null ? null : req.note());
    }

    /** 人工复核队列 */
    @GetMapping("/reviews/pending")
    public List<ManualReviewCase> pendingReviews(@RequestHeader("X-Auth-Token") String token) {
        auth.requireRole(token, Role.CS, Role.STORE_MANAGER);
        return anomalyService.pendingReviews();
    }

    /** 客服人工裁决：UPHOLD_QUOTE / ADJUST_QUOTE / BASIC_ONLY / REJECT_ORDER */
    @PostMapping("/reviews/{reviewId}/verdict")
    public ManualReviewCase verdict(@RequestHeader("X-Auth-Token") String token,
                                    @PathVariable Long reviewId,
                                    @RequestBody ReviewVerdictRequest req) {
        UserAccount u = auth.requireRole(token, Role.CS, Role.STORE_MANAGER);
        ReviewOutcome outcome;
        try {
            outcome = ReviewOutcome.valueOf(req.outcome());
        } catch (Exception e) {
            throw com.citylaundry.common.ApiException.badRequest("未知裁决结论: " + req.outcome());
        }
        return anomalyService.adjudicate(u, reviewId, outcome, req.adjustedExtra(), req.note());
    }

    /** 超时未决策扫描：报价失效并转人工队列 */
    @PostMapping("/expire-sweep")
    public Map<String, Object> expireSweep(@RequestHeader("X-Auth-Token") String token) {
        auth.requireRole(token, Role.CS, Role.STORE_MANAGER);
        return Map.of("expired", anomalyService.expireOverdue());
    }
}
