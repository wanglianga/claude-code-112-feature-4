package com.citylaundry.repo;

import com.citylaundry.model.Enums.Role;
import com.citylaundry.model.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserAccountRepo extends JpaRepository<UserAccount, Long> {
    Optional<UserAccount> findByUsername(String username);
    List<UserAccount> findByRole(Role role);
    Optional<UserAccount> findFirstByRole(Role role);
}
