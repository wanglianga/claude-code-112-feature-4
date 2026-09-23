package com.citylaundry.web;

import com.citylaundry.model.*;
import com.citylaundry.model.Enums.Role;
import com.citylaundry.security.AuthService;
import com.citylaundry.service.MemberService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 会员卡：余额、流水、充值 */
@RestController
@RequestMapping("/api/member")
public class MemberController {
    private final AuthService auth;
    private final MemberService memberService;

    public MemberController(AuthService auth, MemberService memberService) {
        this.auth = auth;
        this.memberService = memberService;
    }

    @GetMapping("/card")
    public Map<String, Object> myCard(@RequestHeader("X-Auth-Token") String token) {
        UserAccount u = auth.requireRole(token, Role.CUSTOMER);
        MemberCard card = memberService.myCard(u.getId());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("card", card);
        resp.put("transactions", card == null ? List.of() : memberService.transactions(card.getId()));
        return resp;
    }

    @PostMapping("/recharge")
    public MemberCard recharge(@RequestHeader("X-Auth-Token") String token,
                               @RequestBody Map<String, Object> body) {
        UserAccount u = auth.requireRole(token, Role.CUSTOMER);
        BigDecimal amount = new BigDecimal(String.valueOf(body.get("amount")));
        return memberService.recharge(u.getId(), amount);
    }
}
