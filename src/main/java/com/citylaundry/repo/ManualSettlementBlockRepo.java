package com.citylaundry.repo;

import com.citylaundry.model.Enums.CheckStatus;
import com.citylaundry.model.ManualSettlementBlock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ManualSettlementBlockRepo extends JpaRepository<ManualSettlementBlock, Long> {
    List<ManualSettlementBlock> findByOrderIdAndStatusOrderByIdAsc(Long orderId, CheckStatus status);
    List<ManualSettlementBlock> findByStatusOrderByCreatedAtAsc(CheckStatus status);
}
