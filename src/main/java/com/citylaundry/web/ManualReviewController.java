package com.citylaundry.web;

import com.citylaundry.dto.ApiDtos.ManualReviewHandleRequest;
import com.citylaundry.model.Enums.ManualReviewResult;
import com.citylaundry.model.Enums.Role;
import com.citylaundry.model.ManualReviewCase;
import com.citylaundry.model.UserAccount;
import com.citylaundry.security.AuthService;
import com.citylaundry.service.ManualReviewService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 人工复核队列：客户申请/超时转入；客服维持、下调（出新版本）、维持原价、仅基础、拒单 */
@RestController
@RequestMapping("/api/manual-reviews")
public class ManualReviewController {
    private final AuthService auth;
    private final ManualReviewService service;

    public ManualReviewController(AuthService auth, ManualReviewService service) {
        this.auth = auth;
        this.service = service;
    }

    @GetMapping("/pending")
    public List<ManualReviewCase> pending(@RequestHeader("X-Auth-Token") String token) {
        auth.requireRole(token, Role.CS, Role.STORE_MANAGER);
        return service.pending();
    }

    @PostMapping("/{reviewId}/handle")
    public ManualReviewCase handle(@RequestHeader("X-Auth-Token") String token,
                                   @PathVariable Long reviewId,
                                   @RequestBody ManualReviewHandleRequest req) {
        UserAccount cs = auth.requireRole(token, Role.CS, Role.STORE_MANAGER);
        ManualReviewResult result;
        try {
            result = ManualReviewResult.valueOf(req.result());
        } catch (Exception e) {
            throw com.citylaundry.common.ApiException.badRequest("未知复核结论: " + req.result());
        }
        return service.handle(cs, reviewId, result, req.adjustedSurcharge(), req.conclusion());
    }
}
