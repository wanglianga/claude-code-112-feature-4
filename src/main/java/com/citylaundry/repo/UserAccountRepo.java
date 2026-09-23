package com.citylaundry.repo;

import com.citylaundry.model.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserAccountRepo extends JpaRepository<UserAccount, Long> {
    Optional<UserAccount> findByUsername(String username);
}
