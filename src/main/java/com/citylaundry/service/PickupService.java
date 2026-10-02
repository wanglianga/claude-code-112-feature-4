package com.citylaundry.service;

import com.citylaundry.common.ApiException;
import com.citylaundry.model.*;
import com.citylaundry.model.Enums.*;
import com.citylaundry.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 收衣员上门拍照：正反面/污渍/洗标/配饰 */
@Service
public class PickupService {
    private final GarmentItemRepo itemRepo;
    private final GarmentPhotoRepo photoRepo;
    private final LaundryOrderRepo orderRepo;
    private final GarmentStateMachine stateMachine;

    public PickupService(GarmentItemRepo itemRepo, GarmentPhotoRepo photoRepo, LaundryOrderRepo orderRepo,
                         GarmentStateMachine stateMachine) {
        this.itemRepo = itemRepo;
        this.photoRepo = photoRepo;
        this.orderRepo = orderRepo;
        this.stateMachine = stateMachine;
    }

    /** 上传衣物照片（收衣员/工厂/复核人/配送共用，按阶段校验角色） */
    @Transactional
    public GarmentPhoto uploadPhoto(UserAccount uploader, Long itemId, PhotoStage stage, String url) {
        return uploadPhoto(uploader, itemId, stage, url, null, null, null);
    }

    /** 上传照片（可携带拍摄时间/位置，异常证据照片可挂 issueId） */
    @Transactional
    public GarmentPhoto uploadPhoto(UserAccount uploader, Long itemId, PhotoStage stage, String url,
                                    LocalDateTime takenAt, String location, Long issueId) {
        GarmentItem item = itemRepo.findById(itemId).orElseThrow(() -> ApiException.notFound("衣物不存在"));
        if (url == null || url.isBlank()) throw ApiException.badRequest("照片 url 不能为空");
        validateStagePermission(uploader.getRole(), stage, item);

        GarmentPhoto photo = new GarmentPhoto();
        photo.setItemId(item.getId());
        photo.setStage(stage);
        photo.setUrl(url);
        photo.setUploadedBy(uploader.getId());
        if (takenAt != null) photo.setTakenAt(takenAt);
        photo.setLocation(location);
        photo.setIssueId(issueId);
        photo = photoRepo.save(photo);

        // 收衣阶段：必需照片齐了之后自动进入 PHOTOGRAPHED
        // 覆盖异常场景：异常登记时照片未齐（REGISTERED），或取件现场已确认追加（EXTRA_APPROVED）后补齐照片
        if ((item.getStatus() == GarmentStatus.REGISTERED || item.getStatus() == GarmentStatus.EXTRA_APPROVED)
                && isPickupStage(stage) && requiredStagesDone(item)) {
            stateMachine.transition(item.getId(), GarmentStatus.PHOTOGRAPHED, "PICKUP_PHOTOS_DONE",
                    "PHOTO", photo.getId(), uploader.getId(), "收衣必需照片齐全");
            maybeMarkOrderPickedUp(item.getOrderId());
        }
        return photo;
    }

    private void validateStagePermission(Role role, PhotoStage stage, GarmentItem item) {
        switch (stage) {
            case PICKUP_FRONT, PICKUP_BACK, STAIN, CARE_LABEL, ACCESSORY -> {
                if (role != Role.PICKUP) throw ApiException.forbidden("收衣照片只能由收衣员上传");
                // 取件阶段异常处理期间（待报价/复核/已确认追加）仍允许补传剩余收衣照片
                if (item.getStatus() != GarmentStatus.REGISTERED && item.getStatus() != GarmentStatus.PHOTOGRAPHED
                        && item.getStatus() != GarmentStatus.ANOMALY_QUOTED
                        && item.getStatus() != GarmentStatus.MANUAL_REVIEW
                        && item.getStatus() != GarmentStatus.EXTRA_APPROVED)
                    throw ApiException.conflict("当前状态不可上传收衣照片: " + item.getStatus());
            }
            case FACTORY_RESHOOT -> {
                if (role != Role.FACTORY) throw ApiException.forbidden("复拍照片只能由工厂上传");
            }
            case SECOND_PHOTO, PACKAGING -> {
                if (role != Role.STORE_MANAGER && role != Role.FACTORY)
                    throw ApiException.forbidden("复核照片只能由复核专人/工厂上传");
            }
            case DELIVERY -> {
                if (role != Role.DELIVERY) throw ApiException.forbidden("交付照片只能由配送员上传");
            }
        }
    }

    private boolean isPickupStage(PhotoStage s) {
        return s == PhotoStage.PICKUP_FRONT || s == PhotoStage.PICKUP_BACK
                || s == PhotoStage.STAIN || s == PhotoStage.CARE_LABEL || s == PhotoStage.ACCESSORY;
    }

    /** 必需照片：正面+反面必有；有污渍需污渍照；有洗标需洗标照；有配饰需配饰照 */
    public List<PhotoStage> requiredStages(GarmentItem item) {
        List<PhotoStage> required = new ArrayList<>(List.of(PhotoStage.PICKUP_FRONT, PhotoStage.PICKUP_BACK));
        if (item.getStains() != null && !item.getStains().isBlank()) required.add(PhotoStage.STAIN);
        if (item.isCareLabelPresent()) required.add(PhotoStage.CARE_LABEL);
        if (item.getAccessories() != null && !item.getAccessories().isBlank()) required.add(PhotoStage.ACCESSORY);
        return required;
    }

    private boolean requiredStagesDone(GarmentItem item) {
        List<GarmentPhoto> photos = photoRepo.findByItemIdOrderByCreatedAtAsc(item.getId());
        for (PhotoStage stage : requiredStages(item)) {
            boolean found = photos.stream().anyMatch(p -> p.getStage() == stage);
            if (!found) return false;
        }
        return true;
    }

    private void maybeMarkOrderPickedUp(Long orderId) {
        List<GarmentItem> items = itemRepo.findByOrderIdOrderByIdAsc(orderId);
        boolean allPhotographed = items.stream().allMatch(i -> i.getStatus() == GarmentStatus.PHOTOGRAPHED);
        if (allPhotographed && !items.isEmpty()) {
            LaundryOrder order = orderRepo.findById(orderId).orElseThrow();
            if (order.getStatus() == OrderStatus.CREATED) {
                order.setStatus(OrderStatus.PICKED_UP);
                orderRepo.save(order);
            }
        }
    }

    public List<GarmentPhoto> photosOf(Long itemId) {
        return photoRepo.findByItemIdOrderByCreatedAtAsc(itemId);
    }
}
