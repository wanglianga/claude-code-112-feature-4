package com.citylaundry.security;

import com.citylaundry.common.ApiException;
import com.citylaundry.model.Enums.Role;
import com.citylaundry.model.UserAccount;
import com.citylaundry.repo.UserAccountRepo;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 简易令牌认证：登录发 token，后续请求带 X-Auth-Token */
@Service
public class AuthService {
    private final UserAccountRepo userRepo;
    private final Map<String, Long> tokens = new ConcurrentHashMap<>();

    public AuthService(UserAccountRepo userRepo) {
        this.userRepo = userRepo;
    }

    public String login(String username, String rawPassword) {
        UserAccount u = userRepo.findByUsername(username)
                .orElseThrow(() -> ApiException.unauthorized("用户名或密码错误"));
        if (!u.getPasswordHash().equals(sha256(rawPassword))) {
            throw ApiException.unauthorized("用户名或密码错误");
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        tokens.put(token, u.getId());
        return token;
    }

    public UserAccount require(String token) {
        if (token == null || token.isBlank()) throw ApiException.unauthorized("缺少 X-Auth-Token");
        Long uid = tokens.get(token);
        if (uid == null) throw ApiException.unauthorized("令牌无效或已过期");
        return userRepo.findById(uid).orElseThrow(() -> ApiException.unauthorized("用户不存在"));
    }

    public UserAccount requireRole(String token, Role... roles) {
        UserAccount u = require(token);
        for (Role r : roles) {
            if (u.getRole() == r) return u;
        }
        throw ApiException.forbidden("当前角色无权执行此操作，需要角色: " + java.util.Arrays.toString(roles));
    }

    public Optional<UserAccount> find(Long id) { return userRepo.findById(id); }

    public static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
