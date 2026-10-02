package com.citylaundry.repo;

import com.citylaundry.model.CustomerDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CustomerDecisionRepo extends JpaRepository<CustomerDecision, Long> {
    List<CustomerDecision> findByIssueIdOrderByCreatedAtAsc(Long issueId);
    List<CustomerDecision> findByItemIdOrderByCreatedAtAsc(Long itemId);
}
