package com.sandbox.imaging.web;

import com.sandbox.imaging.common.ApiException;
import com.sandbox.imaging.domain.AppUser;
import com.sandbox.imaging.repo.AppUserRepository;
import com.sandbox.imaging.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AppUserRepository userRepo;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthController(AppUserRepository userRepo, PasswordEncoder encoder, JwtService jwt) {
        this.userRepo = userRepo;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        AppUser user = userRepo.findByUsername(username)
                .orElseThrow(() -> ApiException.forbidden("用户名或密码错误"));
        if (!encoder.matches(password, user.getPasswordHash())) {
            throw ApiException.forbidden("用户名或密码错误");
        }
        String token = jwt.issue(user.getUsername(), user.getRole(), user.getDisplayName());
        return Map.of("token", token,
                "user", Map.of("username", user.getUsername(),
                        "displayName", user.getDisplayName(), "role", user.getRole()));
    }
}
