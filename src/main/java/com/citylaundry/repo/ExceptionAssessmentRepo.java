package com.citylaundry.repo;

import com.citylaundry.model.ExceptionAssessment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExceptionAssessmentRepo extends JpaRepository<ExceptionAssessment, Long> {
    List<ExceptionAssessment> findByExceptionIdOrderByAssessedAtAsc(Long exceptionId);
    Optional<ExceptionAssessment> findByExceptionIdAndSource(Long exceptionId,
            com.citylaundry.model.Enums.ExceptionSource source);
}
