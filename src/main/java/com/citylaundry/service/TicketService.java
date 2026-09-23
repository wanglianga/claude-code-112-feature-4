package com.citylaundry.service;

import com.citylaundry.common.ApiException;
import com.citylaundry.model.*;
import com.citylaundry.model.Enums.*;
import com.citylaundry.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 异常工单：顾客/收衣员/工厂/客服/配送/财务围绕同一衣物单协同 */
@Service
public class TicketService {
    private final ExceptionTicketRepo ticketRepo;
    private final TicketCommentRepo commentRepo;
    private final GarmentItemRepo itemRepo;
    private final LaundryOrderRepo orderRepo;

    public TicketService(ExceptionTicketRepo ticketRepo, TicketCommentRepo commentRepo,
                         GarmentItemRepo itemRepo, LaundryOrderRepo orderRepo) {
        this.ticketRepo = ticketRepo;
        this.commentRepo = commentRepo;
        this.itemRepo = itemRepo;
        this.orderRepo = orderRepo;
    }

    /** 任意角色对某件衣物发起异常工单 */
    @Transactional
    public ExceptionTicket raise(UserAccount user, Long itemId, ExceptionType type, String description,
                                 String newAddress, LocalDateTime newExpectedDeliveryAt) {
        GarmentItem item = itemRepo.findById(itemId).orElseThrow(() -> ApiException.notFound("衣物不存在"));
        if (description == null || description.isBlank()) throw ApiException.badRequest("异常描述不能为空");
        LaundryOrder order = orderRepo.findById(item.getOrderId()).orElseThrow();

        // 顾客只能对自己的订单发起
        if (user.getRole() == Role.CUSTOMER && !order.getCustomerId().equals(user.getId())) {
            throw ApiException.forbidden("只能对自己的订单发起异常");
        }
        // 顾客质疑新破损：必须在售后期限内
        if (type == ExceptionType.DAMAGE_DISPUTE) {
            if (item.getStatus() != GarmentStatus.DELIVERED) {
                throw ApiException.conflict("衣物未交付，无法质疑新破损: " + item.getStatus());
            }
        }
        if (type == ExceptionType.ADDRESS_CHANGE) {
            if (newAddress == null || newAddress.isBlank()) throw ApiException.badRequest("改地址必须提供 newAddress");
            if (order.getStatus() == OrderStatus.DELIVERED) throw ApiException.conflict("订单已交付，不能改地址");
        }
        if (type == ExceptionType.FACTORY_DELAY) {
            if (newExpectedDeliveryAt == null) throw ApiException.badRequest("工厂延期必须提供 newExpectedDeliveryAt");
        }

        ExceptionTicket t = new ExceptionTicket();
        t.setItemId(item.getId());
        t.setType(type);
        t.setDescription(description);
        t.setNewAddress(newAddress);
        t.setNewExpectedDeliveryAt(newExpectedDeliveryAt);
        t.setRaisedBy(user.getId());
        t.setStatus(TicketStatus.OPEN);
        t.setTicketNo("TMP");
        t = ticketRepo.save(t);
        t.setTicketNo(String.format("T%08d", t.getId()));
        return ticketRepo.save(t);
    }

    /** 系统/工厂内部自动建单（洗标缺失、拒洗等） */
    @Transactional
    public ExceptionTicket raiseSystemTicket(GarmentItem item, ExceptionType type, String description) {
        // 同一件衣物同类型未关闭工单不重复建
        List<ExceptionTicket> existing = ticketRepo.findByItemIdAndTypeAndStatusIn(
                item.getId(), type, List.of(TicketStatus.OPEN, TicketStatus.PROCESSING));
        if (!existing.isEmpty()) return existing.get(0);
        ExceptionTicket t = new ExceptionTicket();
        t.setItemId(item.getId());
        t.setType(type);
        t.setDescription(description);
        t.setRaisedBy(0L); // 0 = 系统
        t.setStatus(TicketStatus.OPEN);
        t.setTicketNo("TMP");
        t = ticketRepo.save(t);
        t.setTicketNo(String.format("T%08d", t.getId()));
        return ticketRepo.save(t);
    }

    /** 客服受理：OPEN → PROCESSING */
    @Transactional
    public ExceptionTicket accept(UserAccount cs, Long ticketId) {
        ExceptionTicket t = getTicket(ticketId);
        if (t.getStatus() != TicketStatus.OPEN) throw ApiException.conflict("工单不是待受理状态: " + t.getStatus());
        t.setStatus(TicketStatus.PROCESSING);
        t.setHandlerId(cs.getId());
        return ticketRepo.save(t);
    }

    /** 客服办结：应用副作用（改地址/延期），工单 → RESOLVED */
    @Transactional
    public ExceptionTicket resolve(UserAccount cs, Long ticketId, String resolution) {
        ExceptionTicket t = getTicket(ticketId);
        if (t.getStatus() != TicketStatus.OPEN && t.getStatus() != TicketStatus.PROCESSING) {
            throw ApiException.conflict("工单已办结: " + t.getStatus());
        }
        if (resolution == null || resolution.isBlank()) throw ApiException.badRequest("办结说明不能为空");
        GarmentItem item = itemRepo.findById(t.getItemId()).orElseThrow();
        LaundryOrder order = orderRepo.findById(item.getOrderId()).orElseThrow();

        switch (t.getType()) {
            case ADDRESS_CHANGE -> {
                if (order.getStatus() == OrderStatus.DELIVERED) throw ApiException.conflict("订单已交付，不能改地址");
                order.setDeliveryAddress(t.getNewAddress());
                orderRepo.save(order);
            }
            case FACTORY_DELAY -> {
                order.setExpectedDeliveryAt(t.getNewExpectedDeliveryAt());
                orderRepo.save(order);
            }
            default -> { /* 其他类型仅记录处理结论 */ }
        }
        t.setStatus(TicketStatus.RESOLVED);
        t.setHandlerId(cs.getId());
        t.setResolution(resolution);
        t.setResolvedAt(LocalDateTime.now());
        return ticketRepo.save(t);
    }

    /** 多角色留言协同 */
    @Transactional
    public TicketComment comment(UserAccount user, Long ticketId, String content) {
        ExceptionTicket t = getTicket(ticketId);
        if (content == null || content.isBlank()) throw ApiException.badRequest("留言不能为空");
        TicketComment c = new TicketComment();
        c.setTicketId(t.getId());
        c.setUserId(user.getId());
        c.setUserRole(user.getRole());
        c.setContent(content);
        return commentRepo.save(c);
    }

    public ExceptionTicket getTicket(Long id) {
        return ticketRepo.findById(id).orElseThrow(() -> ApiException.notFound("工单不存在"));
    }

    public List<ExceptionTicket> listByStatus(TicketStatus status) {
        return status == null ? ticketRepo.findAll() : ticketRepo.findByStatusOrderByCreatedAtAsc(status);
    }

    public List<ExceptionTicket> ticketsOfItem(Long itemId) {
        return ticketRepo.findByItemIdOrderByCreatedAtAsc(itemId);
    }

    public List<TicketComment> commentsOf(Long ticketId) {
        return commentRepo.findByTicketIdOrderByCreatedAtAsc(ticketId);
    }
}
