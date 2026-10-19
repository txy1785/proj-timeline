package com.projtimeline.user;

import com.projtimeline.common.ApiResponse;
import com.projtimeline.common.BusinessException;
import com.projtimeline.common.PageResponse;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final AdminUserService service;
    public UserController(AdminUserService service) { this.service = service; }

    @GetMapping
    ApiResponse<PageResponse<Map<String, Object>>> users(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size) { return ApiResponse.ok(service.list(page, size)); }

    @PostMapping
    ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) { return ApiResponse.ok(service.create(body)); }

    @PutMapping("/{id}")
    ApiResponse<Map<String, Object>> update(@PathVariable long id, @RequestBody Map<String, Object> body) { return ApiResponse.ok(service.update(id, body)); }

    @PutMapping("/{id}/password")
    ApiResponse<Void> password(@PathVariable long id, @RequestBody Map<String, Object> body) {
        Object password = body.get("password"); if (password == null) throw BusinessException.badRequest("password 不能为空");
        service.resetPassword(id, password.toString()); return ApiResponse.ok(null);
    }
}
