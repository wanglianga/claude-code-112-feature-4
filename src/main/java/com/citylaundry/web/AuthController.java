package com.citylaundry.web;

import com.citylaundry.dto.ApiDtos.LoginRequest;
import com.citylaundry.model.UserAccount;
import com.citylaundry.security.AuthService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest req) {
        String token = authService.login(req.username(), req.password());
        UserAccount u = authService.require(token);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("token", token);
        resp.put("userId", u.getId());
        resp.put("username", u.getUsername());
        resp.put("name", u.getName());
        resp.put("role", u.getRole());
        return resp;
    }

    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader("X-Auth-Token") String token) {
        UserAccount u = authService.require(token);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("userId", u.getId());
        resp.put("username", u.getUsername());
        resp.put("name", u.getName());
        resp.put("role", u.getRole());
        resp.put("storeId", u.getStoreId());
        resp.put("siteId", u.getSiteId());
        return resp;
    }
}
