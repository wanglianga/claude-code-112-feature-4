package com.citylaundry.web;

import com.citylaundry.dto.ApiDtos.*;
import com.citylaundry.model.*;
import com.citylaundry.model.Enums.*;
import com.citylaundry.repo.GarmentItemRepo;
import com.citylaundry.repo.ProcessRecordRepo;
import com.citylaundry.repo.TransferRecordRepo;
import com.citylaundry.security.AuthService;
import com.citylaundry.service.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 工厂：入厂、分配工艺、洗护、复拍、多站点流转、拒洗 */
@RestController
@RequestMapping("/api/factory")
public class FactoryController {
    private final AuthService auth;
    private final FactoryService factoryService;
    private final PickupService pickupService;
    private final GarmentItemRepo itemRepo;
    private final ProcessRecordRepo processRecordRepo;
    private final TransferRecordRepo transferRecordRepo;

    public FactoryController(AuthService auth, FactoryService factoryService, PickupService pickupService,
                             GarmentItemRepo itemRepo,
                             ProcessRecordRepo processRecordRepo, TransferRecordRepo transferRecordRepo) {
        this.auth = auth;
        this.factoryService = factoryService;
        this.pickupService = pickupService;
        this.itemRepo = itemRepo;
        this.processRecordRepo = processRecordRepo;
        this.transferRecordRepo = transferRecordRepo;
    }

    /** 衣物清单：可按站点 + 状态筛选 */
    @GetMapping("/items")
    public List<GarmentItem> items(@RequestHeader("X-Auth-Token") String token,
                                   @RequestParam(required = false) Long siteId,
                                   @RequestParam(required = false) String status) {
        UserAccount u = auth.requireRole(token, Role.FACTORY, Role.STORE_MANAGER);
        if (siteId != null && status != null) {
            return factoryService.itemsAtSite(siteId, GarmentStatus.valueOf(status));
        }
        return itemRepo.findAll();
    }

    @PostMapping("/items/{itemId}/receive/{siteId}")
    public GarmentItem receive(@RequestHeader("X-Auth-Token") String token,
                               @PathVariable Long itemId, @PathVariable Long siteId) {
        UserAccount u = auth.requireRole(token, Role.FACTORY);
        return factoryService.receiveAtSite(u, itemId, siteId);
    }

    /** 分配工艺；processType 为空则按材质/洗标/污渍/风险自动判定 */
    @PostMapping("/items/{itemId}/assign-process")
    public ProcessRecord assign(@RequestHeader("X-Auth-Token") String token,
                                @PathVariable Long itemId,
                                @RequestBody AssignProcessRequest req) {
        UserAccount u = auth.requireRole(token, Role.FACTORY);
        ProcessType manual = null;
        if (req.processType() != null && !req.processType().isBlank()) {
            try {
                manual = ProcessType.valueOf(req.processType());
            } catch (IllegalArgumentException e) {
                throw com.citylaundry.common.ApiException.badRequest("未知工艺: " + req.processType());
            }
        }
        return factoryService.assignProcess(u, itemId, manual);
    }

    @PostMapping("/items/{itemId}/start")
    public GarmentItem start(@RequestHeader("X-Auth-Token") String token, @PathVariable Long itemId) {
        auth.requireRole(token, Role.FACTORY);
        return factoryService.startProcess(itemId);
    }

    /** 完成洗护（必须复拍照片）；高价值衣物自动转入专人复核 */
    @PostMapping("/items/{itemId}/finish")
    public GarmentItem finish(@RequestHeader("X-Auth-Token") String token,
                              @PathVariable Long itemId,
                              @RequestBody FinishProcessRequest req) {
        UserAccount u = auth.requireRole(token, Role.FACTORY);
        return factoryService.finishProcess(u, itemId, req.note(), req.reshootUrl());
    }

    @PostMapping("/items/{itemId}/reject")
    public GarmentItem reject(@RequestHeader("X-Auth-Token") String token,
                              @PathVariable Long itemId,
                              @RequestBody RejectRequest req) {
        UserAccount u = auth.requireRole(token, Role.FACTORY);
        return factoryService.reject(u, itemId, req.reason());
    }

    /** 多站点流转 */
    @PostMapping("/items/{itemId}/transfer/{toSiteId}")
    public TransferRecord transfer(@RequestHeader("X-Auth-Token") String token,
                                   @PathVariable Long itemId, @PathVariable Long toSiteId,
                                   @RequestBody(required = false) Map<String, String> body) {
        UserAccount u = auth.requireRole(token, Role.FACTORY);
        String note = body == null ? null : body.get("note");
        return factoryService.transfer(u, itemId, toSiteId, note);
    }

    /** 复核二次拍照(SECOND_PHOTO) / 包装确认(PACKAGING) 照片上传 */
    @PostMapping("/items/{itemId}/photos")
    public GarmentPhoto reviewPhoto(@RequestHeader("X-Auth-Token") String token,
                                    @PathVariable Long itemId,
                                    @RequestBody PhotoRequest req) {
        UserAccount u = auth.requireRole(token, Role.FACTORY, Role.STORE_MANAGER);
        PhotoStage stage = PickupController.parseStage(req.stage());
        return pickupService.uploadPhoto(u, itemId, stage, req.url());
    }

    @GetMapping("/items/{itemId}/processes")
    public List<ProcessRecord> processes(@RequestHeader("X-Auth-Token") String token,
                                         @PathVariable Long itemId) {
        auth.require(token);
        return processRecordRepo.findByItemIdOrderByStartedAtAsc(itemId);
    }

    @GetMapping("/items/{itemId}/transfers")
    public List<TransferRecord> transfers(@RequestHeader("X-Auth-Token") String token,
                                          @PathVariable Long itemId) {
        auth.require(token);
        return transferRecordRepo.findByItemIdOrderByCreatedAtAsc(itemId);
    }
}
