package com.citylaundry.repo;

import com.citylaundry.model.QuoteOption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QuoteOptionRepo extends JpaRepository<QuoteOption, Long> {
    List<QuoteOption> findByQuoteRoundIdOrderByIdAsc(Long quoteRoundId);
}
