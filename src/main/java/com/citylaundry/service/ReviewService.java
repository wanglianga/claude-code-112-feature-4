package com.citylaundry.service;

import com.citylaundry.common.ApiException;
import com.citylaundry.model.*;
import com.citylaundry.model.Enums.*;
import com.citylaundry.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 高价值衣物专人复核：交付前必须完成二次拍照(SECOND_PHOTO)和包装确认(PACKAGING)。
 */
@Service
public class ReviewService {
    private final HighValueReviewRepo reviewRepo;
    private final GarmentItemRepo itemRepo;
    private final GarmentPhotoRepo photoRepo;
    private final FactoryService factoryService;

    public ReviewService(HighValueReviewRepo reviewRepo, GarmentItemRepo itemRepo,
                         GarmentPhotoRepo photoRepo, FactoryService factoryService) {
        this.reviewRepo = reviewRepo;
        this.itemRepo = itemRepo;
        this.photoRepo = photoRepo;
        this.factoryService = factoryService;
    }

    public List<HighValueReview> pendingReviews() {
        return reviewRepo.findByStatus(ReviewStatus.PENDING);
    }

    /** 复核人完成复核：校验二次拍照 + 包装照片齐全后通过 */
    @Transactional
    public HighValueReview complete(UserAccount reviewer, Long itemId, String note) {
        GarmentItem item = itemRepo.findById(itemId).orElseThrow(() -> ApiException.notFound("衣物不存在"));
        HighValueReview review = reviewRepo.findByItemId(itemId)
                .orElseThrow(() -> ApiException.notFound("该衣物没有高价值复核任务"));
        if (review.getStatus() != ReviewStatus.PENDING) {
            throw ApiException.conflict("复核任务已完成");
        }
        List<GarmentPhoto> photos = photoRepo.findByItemIdOrderByCreatedAtAsc(item.getId());
        boolean secondPhoto = photos.stream().anyMatch(p -> p.getStage() == PhotoStage.SECOND_PHOTO);
        boolean packaging = photos.stream().anyMatch(p -> p.getStage() == PhotoStage.PACKAGING);
        if (!secondPhoto) throw ApiException.conflict("复核未通过：缺少交付前二次拍照(SECOND_PHOTO)");
        if (!packaging) throw ApiException.conflict("复核未通过：缺少包装确认照片(PACKAGING)");

        review.setStatus(ReviewStatus.PASSED);
        review.setReviewerId(reviewer.getId());
        review.setNote(note == null || note.isBlank() ? "复核通过：二次拍照与包装确认齐全" : note);
        review.setCompletedAt(LocalDateTime.now());
        review = reviewRepo.save(review);

        if (item.getStatus() == GarmentStatus.IN_REVIEW) {
            factoryService.markReadyForDelivery(item);
        }
        return review;
    }
}
