package com.citylaundry.web;

import com.citylaundry.dto.ApiDtos.ReviewCompleteRequest;
import com.citylaundry.model.*;
import com.citylaundry.model.Enums.Role;
import com.citylaundry.security.AuthService;
import com.citylaundry.service.ReviewService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 高价值衣物专人复核：交付前必须完成二次拍照和包装确认 */
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {
    private final AuthService auth;
    private final ReviewService reviewService;

    public ReviewController(AuthService auth, ReviewService reviewService) {
        this.auth = auth;
        this.reviewService = reviewService;
    }

    /** 待复核任务 */
    @GetMapping("/pending")
    public List<HighValueReview> pending(@RequestHeader("X-Auth-Token") String token) {
        auth.requireRole(token, Role.STORE_MANAGER, Role.FACTORY);
        return reviewService.pendingReviews();
    }

    /** 完成复核：系统校验二次拍照 + 包装确认齐全 */
    @PostMapping("/items/{itemId}/complete")
    public HighValueReview complete(@RequestHeader("X-Auth-Token") String token,
                                    @PathVariable Long itemId,
                                    @RequestBody(required = false) ReviewCompleteRequest req) {
        UserAccount u = auth.requireRole(token, Role.STORE_MANAGER, Role.FACTORY);
        return reviewService.complete(u, itemId, req == null ? null : req.note());
    }
}
