package com.citylaundry.repo;

import com.citylaundry.model.IssueEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IssueEvidenceRepo extends JpaRepository<IssueEvidence, Long> {
    List<IssueEvidence> findByIssueIdOrderByShotRoundAsc(Long issueId);
    List<IssueEvidence> findByItemIdOrderByCreatedAtAsc(Long itemId);
    int countByIssueId(Long issueId);
}
