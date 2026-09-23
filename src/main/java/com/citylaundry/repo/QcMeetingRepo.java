package com.citylaundry.repo;

import com.citylaundry.model.QcMeeting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QcMeetingRepo extends JpaRepository<QcMeeting, Long> {
    List<QcMeeting> findByStoreIdOrderByHeldAtDesc(Long storeId);
}
