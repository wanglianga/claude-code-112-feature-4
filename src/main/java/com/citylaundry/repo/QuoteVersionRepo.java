package com.citylaundry.repo;

import com.citylaundry.model.QuoteVersion;
import com.citylaundry.model.Enums.QuoteStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface QuoteVersionRepo extends JpaRepository<QuoteVersion, Long> {
    Optional<QuoteVersion> findByQuoteNo(String quoteNo);
    List<QuoteVersion> findByIssueIdOrderByVersionNoAsc(Long issueId);
    List<QuoteVersion> findByItemIdOrderByVersionNoAsc(Long itemId);
    Optional<QuoteVersion> findByIssueIdAndStatus(Long issueId, QuoteStatus status);
    int countByIssueId(Long issueId);
}
