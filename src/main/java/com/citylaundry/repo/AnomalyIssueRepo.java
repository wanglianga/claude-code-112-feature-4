package com.citylaundry.repo;

import com.citylaundry.model.AnomalyIssue;
import com.citylaundry.model.Enums.AnomalyStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AnomalyIssueRepo extends JpaRepository<AnomalyIssue, Long> {
    Optional<AnomalyIssue> findByIssueNo(String issueNo);
    List<AnomalyIssue> findByItemIdOrderByCreatedAtAsc(Long itemId);
    List<AnomalyIssue> findByStatusOrderByCreatedAtAsc(AnomalyStatus status);
    List<AnomalyIssue> findByItemIdAndStatusIn(Long itemId, List<AnomalyStatus> statuses);
}
