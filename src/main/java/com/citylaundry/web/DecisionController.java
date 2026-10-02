package com.citylaundry.web;

import com.citylaundry.dto.ApiDtos.DecisionRequest;
import com.citylaundry.dto.ApiDtos.ManualReviewRequest;
import com.citylaundry.dto.ApiDtos.RejectDecisionRequest;
import com.citylaundry.model.CustomerFeedback;
import com.citylaundry.model.Enums.Role;
import com.citylaundry.model.UserAccount;
import com.citylaundry.security.AuthService;
import com.citylaundry.service.DecisionService;
import org.springframework.web.bind.annotation.*;

/** 客户对异常报价版本的决策：确认 / 拒绝(退回或继续) / 申请人工复核 / 确认后撤回 */
@RestController
@RequestMapping("/api/decisions")
public class DecisionController {
    private final AuthService auth;
    private final DecisionService service;

    public DecisionController(AuthService auth, DecisionService service) {
        this.auth = auth;
        this.service = service;
    }

    /** 确认追加报价（确认后才允许占用产能、洗护排产） */
    @PostMapping("/exceptions/{exceptionId}/confirm")
    public CustomerFeedback confirm(@RequestHeader("X-Auth-Token") String token,
                                    @PathVariable Long exceptionId,
                                    @RequestBody(required = false) DecisionRequest req) {
        UserAccount c = auth.requireRole(token, Role.CUSTOMER);
        return service.confirm(c, exceptionId, req == null ? null : req.roundId(),
                req == null ? null : req.note());
    }

    /**
     * 拒绝。mode=REFUND 原路退回并骑手回收；mode=CONTINUE 按 continueOption
     * （KEEP_ORIGINAL_PRICE / BASIC_TREATMENT_ONLY）继续。
     */
    @PostMapping("/exceptions/{exceptionId}/reject")
    public CustomerFeedback reject(@RequestHeader("X-Auth-Token") String token,
                                   @PathVariable Long exceptionId,
                                   @RequestBody RejectDecisionRequest req) {
        UserAccount c = auth.requireRole(token, Role.CUSTOMER);
        return service.reject(c, exceptionId, req.roundId(),
                req.mode() == null ? "REFUND" : req.mode(),
                req.continueOption(), req.note());
    }

    /** 申请人工复核（在决策时限内） */
    @PostMapping("/exceptions/{exceptionId}/manual-review")
    public CustomerFeedback manualReview(@RequestHeader("X-Auth-Token") String token,
                                         @PathVariable Long exceptionId,
                                         @RequestBody ManualReviewRequest req) {
        UserAccount c = auth.requireRole(token, Role.CUSTOMER);
        return service.requestManualReview(c, exceptionId, req.roundId(), req.note());
    }

    /** 确认后排产前撤回：旧版报价作废，释放产能，重新出具新版报价 */
    @PostMapping("/exceptions/{exceptionId}/withdraw")
    public CustomerFeedback withdraw(@RequestHeader("X-Auth-Token") String token,
                                     @PathVariable Long exceptionId,
                                     @RequestBody(required = false) DecisionRequest req) {
        UserAccount c = auth.requireRole(token, Role.CUSTOMER);
        return service.withdraw(c, exceptionId, req == null ? null : req.note());
    }
}
