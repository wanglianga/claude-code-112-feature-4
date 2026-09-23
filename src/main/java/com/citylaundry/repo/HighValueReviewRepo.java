package com.citylaundry.repo;

import com.citylaundry.model.HighValueReview;
import com.citylaundry.model.Enums.ReviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HighValueReviewRepo extends JpaRepository<HighValueReview, Long> {
    Optional<HighValueReview> findByItemId(Long itemId);
    List<HighValueReview> findByStatus(ReviewStatus status);
}
