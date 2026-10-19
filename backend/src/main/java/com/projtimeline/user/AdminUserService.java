package com.projtimeline.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.projtimeline.common.BusinessException;
import com.projtimeline.common.PageResponse;
import com.projtimeline.data.DataAccess;
import com.projtimeline.security.PermissionService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.projtimeline.common.RequestValues.*;

@Service
public class AdminUserService {
    private final UserMapper users;
    private final DataAccess db;
    private final PermissionService permissions;
    private final PasswordEncoder encoder;

    public AdminUserService(UserMapper users, DataAccess db, PermissionService permissions, PasswordEncoder encoder) {
        this.users = users; this.db = db; this.permissions = permissions; this.encoder = encoder;
    }

    public PageResponse<Map<String, Object>> list(int page, int size) {
        requireAdmin(); page = Math.max(1, page); size = Math.min(100, Math.max(1, size));
        Map<String, Object> params = Map.of("offset", (page-1)*size, "size", size);
        return new PageResponse<>(db.list("SELECT id,username,name,email,global_role,active,created_at FROM users ORDER BY created_at DESC,id DESC LIMIT :offset,:size", params), users.selectCount(null), page, size);
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        requireAdmin(); String role = requiredText(body, "globalRole"); checkRole(role); String password = requiredText(body, "password"); checkPassword(password);
        UserEntity user = new UserEntity(); user.setUsername(requiredText(body, "username")); user.setName(requiredText(body, "name")); user.setEmail(text(body, "email"));
        user.setGlobalRole(role); user.setActive(true); user.setPasswordHash(encoder.encode(password)); users.insert(user); return view(user.getId());
    }

    @Transactional
    public Map<String, Object> update(long id, Map<String, Object> body) {
        requireAdmin(); UserEntity user = require(id); String role = requiredText(body, "globalRole"); checkRole(role); Boolean active = bool(body, "active"); if (active == null) active = true;
        if (!active && db.count("SELECT COUNT(*) FROM project_members WHERE user_id=:user AND project_role='PM'", Map.of("user", id)) > 0) throw BusinessException.conflict("停用项目经理前必须先转交项目");
        user.setName(requiredText(body, "name")); user.setEmail(text(body, "email")); user.setGlobalRole(role); user.setActive(active); users.updateById(user); return view(id);
    }

    @Transactional
    public void resetPassword(long id, String password) { requireAdmin(); checkPassword(password); UserEntity user = require(id); user.setPasswordHash(encoder.encode(password)); users.updateById(user); }

    private UserEntity require(long id) { UserEntity user = users.selectById(id); if (user == null) throw BusinessException.notFound("用户不存在"); return user; }
    private Map<String, Object> view(long id) { return db.one("SELECT id,username,name,email,global_role,active,created_at FROM users WHERE id=:id", Map.of("id", id), "用户不存在"); }
    private void requireAdmin() { if (!permissions.current().isAdmin()) throw BusinessException.forbidden("仅管理员可操作"); }
    private static void checkRole(String role) { if (!Set.of("ADMIN","USER","VIEWER").contains(role)) throw BusinessException.badRequest("globalRole 无效"); }
    private static void checkPassword(String password) { if (password == null || password.length()<8 || password.length()>72) throw BusinessException.badRequest("密码长度必须为 8-72"); }
}
