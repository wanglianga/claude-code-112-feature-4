package com.citylaundry.repo;

import com.citylaundry.model.Store;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreRepo extends JpaRepository<Store, Long> {
}
