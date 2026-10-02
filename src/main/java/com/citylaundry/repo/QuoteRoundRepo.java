package com.citylaundry.repo;

import com.citylaundry.model.Enums.QuoteRoundStatus;
import com.citylaundry.model.QuoteRound;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface QuoteRoundRepo extends JpaRepository<QuoteRound, Long> {
    Optional<QuoteRound> findByQuoteNo(String quoteNo);
    List<QuoteRound> findByExceptionIdOrderByVersionAsc(Long exceptionId);
    Optional<QuoteRound> findFirstByExceptionIdOrderByVersionDesc(Long exceptionId);
    List<QuoteRound> findByItemIdOrderByVersionAsc(Long itemId);
    List<QuoteRound> findByStatus(QuoteRoundStatus status);
}
