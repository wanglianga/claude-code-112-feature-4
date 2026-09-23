package com.citylaundry.web;

import com.citylaundry.model.*;
import com.citylaundry.repo.*;
import com.citylaundry.security.AuthService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** 衣物追溯：独立编号 + 照片 + 工艺 + 交接 + 售后 + 赔付全链路 */
@RestController
@RequestMapping("/api/garments")
public class GarmentController {
    private final AuthService auth;
    private final GarmentItemRepo itemRepo;
    private final GarmentPhotoRepo photoRepo;
    private final ProcessRecordRepo processRepo;
    private final TransferRecordRepo transferRepo;
    private final ExceptionTicketRepo ticketRepo;
    private final CompensationCaseRepo caseRepo;
    private final AfterSalesRecordRepo afterSalesRepo;
    private final HighValueReviewRepo reviewRepo;

    public GarmentController(AuthService auth, GarmentItemRepo itemRepo, GarmentPhotoRepo photoRepo,
                             ProcessRecordRepo processRepo, TransferRecordRepo transferRepo,
                             ExceptionTicketRepo ticketRepo, CompensationCaseRepo caseRepo,
                             AfterSalesRecordRepo afterSalesRepo, HighValueReviewRepo reviewRepo) {
        this.auth = auth;
        this.itemRepo = itemRepo;
        this.photoRepo = photoRepo;
        this.processRepo = processRepo;
        this.transferRepo = transferRepo;
        this.ticketRepo = ticketRepo;
        this.caseRepo = caseRepo;
        this.afterSalesRepo = afterSalesRepo;
        this.reviewRepo = reviewRepo;
    }

    /** 按独立编号追溯单件衣物全链路记录 */
    @GetMapping("/{itemCode}/trace")
    public Map<String, Object> trace(@RequestHeader("X-Auth-Token") String token,
                                     @PathVariable String itemCode) {
        auth.require(token);
        GarmentItem item = itemRepo.findByItemCode(itemCode)
                .orElseThrow(() -> com.citylaundry.common.ApiException.notFound("衣物编号不存在: " + itemCode));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("item", item);
        resp.put("photos", photoRepo.findByItemIdOrderByCreatedAtAsc(item.getId()));
        resp.put("processes", processRepo.findByItemIdOrderByStartedAtAsc(item.getId()));
        resp.put("transfers", transferRepo.findByItemIdOrderByCreatedAtAsc(item.getId()));
        resp.put("tickets", ticketRepo.findByItemIdOrderByCreatedAtAsc(item.getId()));
        resp.put("compensations", caseRepo.findByItemId(item.getId()));
        resp.put("afterSales", afterSalesRepo.findByItemId(item.getId()).orElse(null));
        resp.put("highValueReview", reviewRepo.findByItemId(item.getId()).orElse(null));
        return resp;
    }
}
