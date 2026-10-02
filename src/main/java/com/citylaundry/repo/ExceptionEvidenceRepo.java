package com.citylaundry.repo;

import com.citylaundry.model.ExceptionEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExceptionEvidenceRepo extends JpaRepository<ExceptionEvidence, Long> {
    List<ExceptionEvidence> findByExceptionIdOrderByRoundNoAscIdAsc(Long exceptionId);
    List<ExceptionEvidence> findByItemIdOrderByCapturedAtAsc(Long itemId);
    int countByExceptionId(Long exceptionId);
}
