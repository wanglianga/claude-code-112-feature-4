package com.citylaundry.web;

import com.citylaundry.common.ApiException;
import com.citylaundry.model.LaundryOrder;
import com.citylaundry.model.Enums.Role;
import com.citylaundry.model.UserAccount;
import com.citylaundry.repo.LaundryOrderRepo;
import com.citylaundry.security.AuthService;
import com.citylaundry.service.DossierService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 取洗档案聚合接口：客服、门店、客户围绕同一份事实核对——
 * 衣物明细、异常证据、报价版本决策、客户反馈、履约状态、支付/退款、责任归属、时间线。
 */
@RestController
@RequestMapping("/api/dossiers")
public class DossierController {
    private final AuthService auth;
    private final DossierService dossier;
    private final LaundryOrderRepo orderRepo;

    public DossierController(AuthService auth, DossierService dossier, LaundryOrderRepo orderRepo) {
        this.auth = auth;
        this.dossier = dossier;
        this.orderRepo = orderRepo;
    }

    @GetMapping("/orders/{orderId}")
    public Map<String, Object> ofOrder(@RequestHeader("X-Auth-Token") String token,
                                       @PathVariable Long orderId) {
        UserAccount u = auth.require(token);
        LaundryOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> ApiException.notFound("订单不存在"));
        if (u.getRole() == Role.CUSTOMER && !order.getCustomerId().equals(u.getId())) {
            throw ApiException.forbidden("只能查看自己订单的档案");
        }
        return dossier.ofOrder(orderId);
    }
}
