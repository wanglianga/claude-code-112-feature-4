package com.citylaundry.repo;

import com.citylaundry.model.ManualReviewCase;
import com.citylaundry.model.Enums.ReviewOutcome;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ManualReviewCaseRepo extends JpaRepository<ManualReviewCase, Long> {
    Optional<ManualReviewCase> findByReviewNo(String reviewNo);
    List<ManualReviewCase> findByOutcomeOrderByCreatedAtAsc(ReviewOutcome outcome);
    List<ManualReviewCase> findByIssueIdOrderByCreatedAtAsc(Long issueId);
}
