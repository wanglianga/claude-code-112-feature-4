package com.citylaundry.web;

import com.citylaundry.dto.ApiDtos.*;
import com.citylaundry.model.*;
import com.citylaundry.model.Enums.*;
import com.citylaundry.security.AuthService;
import com.citylaundry.service.TicketService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 异常工单：洗标缺失/污渍无法去除/缩水/配饰丢失/改地址/工厂延期/质疑新破损 */
@RestController
@RequestMapping("/api/tickets")
public class TicketController {
    private final AuthService auth;
    private final TicketService ticketService;

    public TicketController(AuthService auth, TicketService ticketService) {
        this.auth = auth;
        this.ticketService = ticketService;
    }

    /** 任意角色对某件衣物发起异常 */
    @PostMapping
    public ExceptionTicket raise(@RequestHeader("X-Auth-Token") String token,
                                 @RequestBody CreateTicketRequest req) {
        UserAccount u = auth.require(token);
        ExceptionType type;
        try {
            type = ExceptionType.valueOf(req.type());
        } catch (Exception e) {
            throw com.citylaundry.common.ApiException.badRequest("未知异常类型: " + req.type());
        }
        return ticketService.raise(u, req.itemId(), type, req.description(),
                req.newAddress(), req.newExpectedDeliveryAt());
    }

    @GetMapping
    public List<ExceptionTicket> list(@RequestHeader("X-Auth-Token") String token,
                                      @RequestParam(required = false) String status) {
        auth.requireRole(token, Role.CS, Role.FACTORY, Role.STORE_MANAGER, Role.FINANCE, Role.PICKUP, Role.DELIVERY);
        TicketStatus s = null;
        if (status != null && !status.isBlank()) {
            try {
                s = TicketStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw com.citylaundry.common.ApiException.badRequest("未知工单状态: " + status);
            }
        }
        return ticketService.listByStatus(s);
    }

    @GetMapping("/{ticketId}")
    public java.util.Map<String, Object> detail(@RequestHeader("X-Auth-Token") String token,
                                                @PathVariable Long ticketId) {
        auth.require(token);
        java.util.Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("ticket", ticketService.getTicket(ticketId));
        resp.put("comments", ticketService.commentsOf(ticketId));
        return resp;
    }

    /** 客服受理 */
    @PostMapping("/{ticketId}/accept")
    public ExceptionTicket accept(@RequestHeader("X-Auth-Token") String token,
                                  @PathVariable Long ticketId) {
        UserAccount cs = auth.requireRole(token, Role.CS);
        return ticketService.accept(cs, ticketId);
    }

    /** 客服办结（改地址/延期等副作用在此生效） */
    @PostMapping("/{ticketId}/resolve")
    public ExceptionTicket resolve(@RequestHeader("X-Auth-Token") String token,
                                   @PathVariable Long ticketId,
                                   @RequestBody ResolveTicketRequest req) {
        UserAccount cs = auth.requireRole(token, Role.CS);
        return ticketService.resolve(cs, ticketId, req.resolution());
    }

    /** 多角色留言协同 */
    @PostMapping("/{ticketId}/comments")
    public TicketComment comment(@RequestHeader("X-Auth-Token") String token,
                                 @PathVariable Long ticketId,
                                 @RequestBody CommentRequest req) {
        UserAccount u = auth.require(token);
        return ticketService.comment(u, ticketId, req.content());
    }
}
