package com.citylaundry.repo;

import com.citylaundry.model.CompensationCase;
import com.citylaundry.model.Enums.CompensationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompensationCaseRepo extends JpaRepository<CompensationCase, Long> {
    Optional<CompensationCase> findByCaseNo(String caseNo);
    List<CompensationCase> findByItemId(Long itemId);
    List<CompensationCase> findByTicketId(Long ticketId);
    List<CompensationCase> findByStatus(CompensationStatus status);
    List<CompensationCase> findByReviewedInQcFalseAndStatusIn(List<CompensationStatus> statuses);
}
