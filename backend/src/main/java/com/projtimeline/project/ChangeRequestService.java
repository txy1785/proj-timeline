package com.projtimeline.project;

import com.projtimeline.common.BusinessException;
import com.projtimeline.data.DataAccess;
import com.projtimeline.notification.NotificationService;
import com.projtimeline.security.PermissionService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

import static com.projtimeline.common.RequestValues.*;
import static com.projtimeline.security.PermissionService.number;

/**
 * 模块变更申请：没有模块编辑权限的项目成员对「名称 / 描述 / 负责人」提申请，PM 审批通过后由服务端自动改模块。
 * <p>
 * 权限：提交需要项目成员且非 VIEWER（`requireProjectActive`），已有直接编辑权限的人不需要走这条流程；
 * 审批仅 PM/ADMIN；撤回仅申请人本人。同一模块同时只允许一个待审批申请（数据库唯一键 + 服务层友好提示）。
 * 加锁顺序统一为「模块行 → 申请行」，与 `deleteModule` 一致，避免互相等待。
 */
@Service
public class ChangeRequestService {
    private static final String BASE_SQL = """
            SELECT r.id, r.project_id, r.module_id, r.requested_by, r.name, r.description, r.owner_id, r.reason,
                   r.status, r.decided_by, r.decided_at, r.decision_remark, r.created_at,
                   m.name AS module_name, m.owner_id AS module_owner_id,
                   u.name AS requester_name, d.name AS decider_name, o.name AS owner_name
            FROM module_change_requests r
            JOIN modules m ON m.id = r.module_id
            JOIN users u ON u.id = r.requested_by
            LEFT JOIN users d ON d.id = r.decided_by
            LEFT JOIN users o ON o.id = r.owner_id
            """;

    private final DataAccess db;
    private final PermissionService permissions;
    private final NotificationService notifications;
    private final Clock clock;

    public ChangeRequestService(DataAccess db, PermissionService permissions, NotificationService notifications, Clock clock) {
        this.db = db; this.permissions = permissions; this.notifications = notifications; this.clock = clock;
    }

    /** PM/ADMIN 看本项目全部申请；其他成员只看自己提交的。status=PENDING 时只返回待审批。 */
    public List<Map<String, Object>> list(long projectId, String status) {
        permissions.requireProjectRead(projectId);
        StringBuilder sql = new StringBuilder(BASE_SQL).append(" WHERE r.project_id=:project");
        Map<String, Object> params = new HashMap<>();
        params.put("project", projectId);
        if (!canDecide(projectId)) { sql.append(" AND r.requested_by=:mine"); params.put("mine", permissions.current().id()); }
        if ("PENDING".equalsIgnoreCase(status)) sql.append(" AND r.status='PENDING'");
        sql.append(" ORDER BY r.status='PENDING' DESC, r.created_at DESC, r.id DESC");
        return db.list(sql.toString(), params);
    }

    @Transactional
    public Map<String, Object> create(long moduleId, Map<String, Object> body) {
        Map<String, Object> module = db.one("SELECT id,project_id,owner_id,name,description FROM modules WHERE id=:id", Map.of("id", moduleId), "模块不存在");
        long projectId = number(module.get("projectId"));
        permissions.requireProjectActive(projectId);
        long userId = permissions.current().id();
        if (permissions.current().isAdmin() || permissions.isPm(projectId) || number(module.get("ownerId")) == userId) {
            throw BusinessException.conflict("你已有该模块的直接编辑权限，请直接修改");
        }
        String name = requiredText(body, "name");
        if (name.length() > 100) throw BusinessException.badRequest("模块名称不能超过 100 字");
        String description = text(body, "description");
        Long requestedOwner = longValue(body, "ownerId");
        long owner = requestedOwner == null ? number(module.get("ownerId")) : requestedOwner;
        permissions.requireAssignable(projectId, owner);
        String reason = text(body, "reason");
        if (reason != null && reason.length() > 500) throw BusinessException.badRequest("申请说明不能超过 500 字");
        if (name.equals(Objects.toString(module.get("name"))) && Objects.equals(description, module.get("description")) && owner == number(module.get("ownerId"))) {
            throw BusinessException.badRequest("申请内容与当前模块一致，无需提交");
        }
        if (db.optional("SELECT id FROM module_change_requests WHERE module_id=:module AND status='PENDING'", Map.of("module", moduleId)) != null) {
            throw BusinessException.conflict("该模块已有待审批的变更申请");
        }
        Map<String, Object> values = new HashMap<>();
        values.put("project", projectId); values.put("module", moduleId); values.put("requester", userId);
        values.put("name", name); values.put("description", description); values.put("owner", owner); values.put("reason", reason);
        long id;
        try {
            id = db.insert("""
                    INSERT INTO module_change_requests(project_id,module_id,requested_by,name,description,owner_id,reason,status)
                    VALUES(:project,:module,:requester,:name,:description,:owner,:reason,'PENDING')
                    """, values);
        } catch (DuplicateKeyException ex) {
            // 并发下唯一键兜底：转成与服务层一致的提示，而不是「数据约束冲突」
            throw BusinessException.conflict("该模块已有待审批的变更申请");
        }
        notifications.notifyProjectPm(projectId, "CHANGE_REQUEST_SUBMITTED", "模块「" + name + "」变更待审批", "/projects/" + projectId + "/settings");
        return request(id);
    }

    /** 审批：APPROVED 时在同一事务里改模块（名称/描述/负责人），REJECTED 只记录意见。 */
    @Transactional
    public Map<String, Object> decide(long id, boolean approved, String remark) {
        long projectId = projectOfRequest(id);
        permissions.requireProjectManager(projectId);
        return decideLocked(projectId, id, approved, remark);
    }

    @Transactional
    public Map<String, Object> cancel(long id) {
        Map<String, Object> request = request(id);
        if (number(request.get("requestedBy")) != permissions.current().id()) {
            throw BusinessException.forbidden("只能撤回自己提交的申请");
        }
        permissions.requireProjectActive(number(request.get("projectId")));
        Map<String, Object> locked = db.one("SELECT id,status FROM module_change_requests WHERE id=:id FOR UPDATE", Map.of("id", id), "申请不存在");
        if (!"PENDING".equals(Objects.toString(locked.get("status")))) throw BusinessException.conflict("该申请已处理，无法撤回");
        if (db.update("UPDATE module_change_requests SET status='CANCELLED' WHERE id=:id AND status='PENDING'", Map.of("id", id)) != 1) {
            throw BusinessException.conflict("该申请已处理，无法撤回");
        }
        return request(id);
    }

    private long projectOfRequest(long id) {
        return number(db.one("SELECT project_id FROM module_change_requests WHERE id=:id", Map.of("id", id), "申请不存在").get("projectId"));
    }

    private Map<String, Object> decideLocked(long projectId, long id, boolean approved, String remark) {
        // 与 deleteModule 同向加锁：先模块行，再申请行
        long moduleId = number(db.one("SELECT module_id FROM module_change_requests WHERE id=:id", Map.of("id", id), "申请不存在").get("moduleId"));
        Map<String, Object> module = db.one("SELECT id,project_id FROM modules WHERE id=:id FOR UPDATE", Map.of("id", moduleId), "模块不存在");
        Map<String, Object> locked = db.one("SELECT status FROM module_change_requests WHERE id=:id FOR UPDATE", Map.of("id", id), "申请不存在");
        if (!"PENDING".equals(Objects.toString(locked.get("status")))) throw BusinessException.conflict("该申请已处理");
        Map<String, Object> request = request(id);
        String note = remark == null || remark.isBlank() ? null : remark.trim();
        if (note != null && note.length() > 500) throw BusinessException.badRequest("审批意见不能超过 500 字");
        if (!approved && note == null) throw BusinessException.badRequest("驳回时必须填写原因");
        if (approved) {
            long owner = number(request.get("ownerId"));
            permissions.requireAssignable(projectId, owner);
            // description 可能是 null，不能用 Map.of（不接受 null 值）
            Map<String, Object> moduleValues = new HashMap<>();
            moduleValues.put("id", number(module.get("id")));
            moduleValues.put("name", request.get("name"));
            moduleValues.put("description", request.get("description"));
            moduleValues.put("owner", owner);
            db.update("UPDATE modules SET name=:name,description=:description,owner_id=:owner WHERE id=:id", moduleValues);
        }
        Map<String, Object> decision = new HashMap<>();
        decision.put("id", id);
        decision.put("status", approved ? "APPROVED" : "REJECTED");
        decision.put("by", permissions.current().id());
        decision.put("at", LocalDateTime.now(clock));
        decision.put("remark", note);
        if (db.update("""
                UPDATE module_change_requests
                SET status=:status, decided_by=:by, decided_at=:at, decision_remark=:remark
                WHERE id=:id AND status='PENDING'
                """, decision) != 1) {
            throw BusinessException.conflict("该申请已处理");
        }
        String moduleName = Objects.toString(request.get("moduleName"), "");
        notifications.notify(number(request.get("requestedBy")), "CHANGE_REQUEST_DECIDED",
                approved ? "模块「" + moduleName + "」变更申请已通过" : "模块「" + moduleName + "」变更申请被驳回",
                "/projects/" + projectId + "/settings");
        return request(id);
    }

    private Map<String, Object> request(long id) {
        return db.one(BASE_SQL + " WHERE r.id=:id", Map.of("id", id), "申请不存在");
    }

    private boolean canDecide(long projectId) {
        return permissions.current().isAdmin() || permissions.isPm(projectId);
    }
}
