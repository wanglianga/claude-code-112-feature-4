package com.citylaundry.repo;

import com.citylaundry.model.SettlementBlock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SettlementBlockRepo extends JpaRepository<SettlementBlock, Long> {
    List<SettlementBlock> findByResolvedFalseOrderByCreatedAtAsc();
    List<SettlementBlock> findByOrderIdOrderByCreatedAtAsc(Long orderId);
    List<SettlementBlock> findByItemIdOrderByCreatedAtAsc(Long itemId);
}
