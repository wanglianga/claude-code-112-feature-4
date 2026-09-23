package com.citylaundry.repo;

import com.citylaundry.model.MemberCard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberCardRepo extends JpaRepository<MemberCard, Long> {
    Optional<MemberCard> findByCardNo(String cardNo);
    Optional<MemberCard> findByCustomerId(Long customerId);
}
