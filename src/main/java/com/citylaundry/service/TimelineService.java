package com.citylaundry.service;

import com.citylaundry.model.OrderTimelineEvent;
import com.citylaundry.model.UserAccount;
import com.citylaundry.repo.OrderTimelineEventRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 订单档案时间线：append-only 审计事件，客服/门店/客户核对同一事实 */
@Service
public class TimelineService {
    private final OrderTimelineEventRepo repo;

    public TimelineService(OrderTimelineEventRepo repo) {
        this.repo = repo;
    }

    @Transactional
    public OrderTimelineEvent add(Long orderId, Long itemId, String type, String detail,
                                  UserAccount actor, String refNo) {
        OrderTimelineEvent e = new OrderTimelineEvent();
        e.setOrderId(orderId);
        e.setItemId(itemId);
        e.setType(type);
        e.setDetail(detail);
        e.setActorId(actor == null ? 0L : actor.getId());
        e.setActorRole(actor == null ? "SYSTEM" : actor.getRole().name());
        e.setRefNo(refNo);
        return repo.save(e);
    }

    @Transactional
    public OrderTimelineEvent add(Long orderId, Long itemId, String type, String detail,
                                  Long actorId, String actorRole, String refNo) {
        OrderTimelineEvent e = new OrderTimelineEvent();
        e.setOrderId(orderId);
        e.setItemId(itemId);
        e.setType(type);
        e.setDetail(detail);
        e.setActorId(actorId == null ? 0L : actorId);
        e.setActorRole(actorRole == null ? "SYSTEM" : actorRole);
        e.setRefNo(refNo);
        return repo.save(e);
    }

    @Transactional(readOnly = true)
    public List<OrderTimelineEvent> ofOrder(Long orderId) {
        return repo.findByOrderIdOrderByIdAsc(orderId);
    }

    @Transactional(readOnly = true)
    public List<OrderTimelineEvent> ofItem(Long orderId, Long itemId) {
        return repo.findByOrderIdAndItemIdOrderByIdAsc(orderId, itemId);
    }
}
