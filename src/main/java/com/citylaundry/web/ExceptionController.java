package com.citylaundry.web;

import com.citylaundry.dto.ApiDtos.*;
import com.citylaundry.model.*;
import com.citylaundry.model.Enums.*;
import com.citylaundry.security.AuthService;
import com.citylaundry.service.ExceptionService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 异常项与规则报价：
 * 建异常（附原始证据）→ 多次补拍 / 骑手门店各自研判 → 规则生成 4 候选报价版本。
 */
@RestController
@RequestMapping("/api/exceptions")
public class ExceptionController {
    private final AuthService auth;
    private final ExceptionService service;

    public ExceptionController(AuthService auth, ExceptionService service) {
        this.auth = auth;
        this.service = service;
    }

    /** 骑手/门店/工厂发现材质不符、高风险污渍、配件缺失、不可去除瑕疵 → 建异常并自动出首版报价 */
    @PostMapping
    public GarmentException create(@RequestHeader("X-Auth-Token") String token,
                                   @RequestBody CreateExceptionRequest req) {
        UserAccount u = auth.require(token);
        ExceptionCategory category;
        try {
            category = ExceptionCategory.valueOf(req.category());
        } catch (Exception e) {
            throw com.citylaundry.common.ApiException.badRequest("未知异常类别: " + req.category());
        }
        Severity severity = null;
        if (req.severity() != null && !req.severity().isBlank()) {
            try {
                severity = Severity.valueOf(req.severity());
            } catch (Exception e) {
                throw com.citylaundry.common.ApiException.badRequest("未知严重度: " + req.severity());
            }
        }
        return service.create(u, req.itemId(), category, req.description(), req.observedMaterial(),
                req.suggestedAction(), severity, req.photoUrl(), req.capturedAt(), req.location(),
                req.riskLevel(), req.washable(), req.suggestedSurcharge());
    }

    /** 同一衣物多次补拍：证据按轮次留存；如已报价会自动 SUPERSEDED 旧版并重出新版本 */
    @PostMapping("/{exceptionId}/evidence")
    public ExceptionEvidence addEvidence(@RequestHeader("X-Auth-Token") String token,
                                         @PathVariable Long exceptionId,
                                         @RequestBody EvidenceRequest req) {
        UserAccount u = auth.require(token);
        PhotoStage stage = null;
        if (req.stage() != null && !req.stage().isBlank()) {
            try {
                stage = PhotoStage.valueOf(req.stage());
            } catch (Exception e) {
                throw com.citylaundry.common.ApiException.badRequest("未知证据阶段: " + req.stage());
            }
        }
        return service.addEvidence(u, exceptionId, req.photoUrl(), req.capturedAt(),
                req.location(), req.note(), stage);
    }

    /** 追加研判（骑手与门店判断不一致时双方判断均保留，不覆盖） */
    @PostMapping("/{exceptionId}/assessments")
    public ExceptionAssessment addAssessment(@RequestHeader("X-Auth-Token") String token,
                                             @PathVariable Long exceptionId,
                                             @RequestBody AssessmentRequest req) {
        UserAccount u = auth.require(token);
        return service.addAssessment(u, exceptionId, req.judgedMaterial(),
                req.riskLevel() == null ? "MEDIUM" : req.riskLevel(),
                req.washable() == null || req.washable(),
                req.recommendation(), req.suggestedSurcharge());
    }

    /** 手动（重新）生成报价版本：INITIAL/RESHOOT/RIDER_STORE_DISAGREE/MANUAL_REVIEW/CUSTOMER_WITHDRAW */
    @PostMapping("/{exceptionId}/quote")
    public QuoteRound issueQuote(@RequestHeader("X-Auth-Token") String token,
                                 @PathVariable Long exceptionId,
                                 @RequestBody(required = false) IssueQuoteRequest req) {
        UserAccount u = auth.requireRole(token, Role.STORE_MANAGER, Role.CS, Role.FACTORY);
        String reason = req == null || req.reason() == null ? "INITIAL" : req.reason();
        return service.issueQuote(exceptionId, reason, req == null ? null : req.surcharge(), u);
    }

    @GetMapping("/{exceptionId}")
    public Map<String, Object> detail(@RequestHeader("X-Auth-Token") String token,
                                      @PathVariable Long exceptionId) {
        auth.require(token);
        Map<String, Object> m = new LinkedHashMap<>(service.detail(exceptionId));
        return m;
    }

    /** 待客户决策的异常（客服/门店工作台） */
    @GetMapping("/pending-customer")
    public List<GarmentException> pending(@RequestHeader("X-Auth-Token") String token) {
        auth.requireRole(token, Role.CS, Role.STORE_MANAGER, Role.FACTORY);
        return service.pendingCustomer();
    }

    /** 客户超时未决策异常 */
    @GetMapping("/overdue")
    public List<GarmentException> overdue(@RequestHeader("X-Auth-Token") String token) {
        auth.requireRole(token, Role.CS, Role.STORE_MANAGER);
        return service.overdue();
    }
}
