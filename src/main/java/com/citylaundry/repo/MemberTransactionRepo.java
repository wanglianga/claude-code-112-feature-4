package com.citylaundry.repo;

import com.citylaundry.model.MemberTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MemberTransactionRepo extends JpaRepository<MemberTransaction, Long> {
    List<MemberTransaction> findByCardIdOrderByCreatedAtDesc(Long cardId);
    List<MemberTransaction> findByOrderId(Long orderId);
    Optional<MemberTransaction> findByIdempotencyKey(String idempotencyKey);
}
