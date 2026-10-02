package com.citylaundry.repo;

import com.citylaundry.model.MemberTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MemberTransactionRepo extends JpaRepository<MemberTransaction, Long> {
    List<MemberTransaction> findByCardIdOrderByCreatedAtDesc(Long cardId);
    List<MemberTransaction> findByOrderId(Long orderId);
    boolean existsByIdempotencyKey(String idempotencyKey);
    java.util.Optional<MemberTransaction> findFirstByIdempotencyKey(String idempotencyKey);
}
