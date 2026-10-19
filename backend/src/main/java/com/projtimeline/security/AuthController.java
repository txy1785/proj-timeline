package com.projtimeline.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.projtimeline.common.ApiResponse;
import com.projtimeline.common.BusinessException;
import com.projtimeline.user.UserEntity;
import com.projtimeline.user.UserMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserMapper users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(UserMapper users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        UserEntity user = users.selectOne(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getUsername, request.username()));
        if (user == null || !Boolean.TRUE.equals(user.getActive()) || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw BusinessException.unauthorized("用户名或密码错误");
        }
        JwtService.Token token = jwtService.issue(user.getId());
        return ApiResponse.ok(new LoginResponse(token.token(), token.expiresAt(), view(user)));
    }

    @GetMapping("/me")
    ApiResponse<UserView> me(Authentication authentication) {
        CurrentUser current = (CurrentUser) authentication.getPrincipal();
        UserEntity user = users.selectById(current.id());
        return ApiResponse.ok(view(user));
    }

    private UserView view(UserEntity user) {
        return new UserView(user.getId(), user.getUsername(), user.getName(), user.getEmail(), user.getGlobalRole(), user.getActive());
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record LoginResponse(String token, Instant expiresAt, UserView user) {}
    public record UserView(long id, String username, String name, String email, String globalRole, boolean active) {}
}
