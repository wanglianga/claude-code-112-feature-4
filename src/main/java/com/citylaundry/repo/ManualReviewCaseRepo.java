package com.citylaundry.repo;

import com.citylaundry.model.Enums.ManualReviewResult;
import com.citylaundry.model.ManualReviewCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ManualReviewCaseRepo extends JpaRepository<ManualReviewCase, Long> {
    Optional<ManualReviewCase> findByReviewNo(String reviewNo);
    List<ManualReviewCase> findByResult(ManualReviewResult result);
    List<ManualReviewCase> findByExceptionIdOrderByCreatedAtAsc(Long exceptionId);
}
