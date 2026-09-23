package com.citylaundry.repo;

import com.citylaundry.model.Enums.ExceptionType;
import com.citylaundry.model.Enums.TicketStatus;
import com.citylaundry.model.ExceptionTicket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExceptionTicketRepo extends JpaRepository<ExceptionTicket, Long> {
    Optional<ExceptionTicket> findByTicketNo(String ticketNo);
    List<ExceptionTicket> findByItemIdOrderByCreatedAtAsc(Long itemId);
    List<ExceptionTicket> findByStatusOrderByCreatedAtAsc(TicketStatus status);
    List<ExceptionTicket> findByItemIdAndTypeAndStatusIn(Long itemId, ExceptionType type, List<TicketStatus> statuses);
}
