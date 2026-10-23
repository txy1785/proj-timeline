package com.projtimeline.security;

import com.projtimeline.common.BusinessException;
import com.projtimeline.data.DataAccess;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class PermissionService {
    private final DataAccess db;

    public PermissionService(DataAccess db) { this.db = db; }

    public CurrentUser current() {
        Object value = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(value instanceof CurrentUser user)) throw BusinessException.unauthorized("未登录");
        return user;
    }

    public Map<String, Object> requireProjectRead(long projectId) {
        Map<String, Object> project = project(projectId);
        CurrentUser user = current();
        if (!user.isAdmin() && !user.isViewer() && membership(projectId, user.id()) == null) {
            throw BusinessException.forbidden("无权访问该项目");
        }
        return project;
    }

    public Map<String, Object> requireProjectActive(long projectId) {
        Map<String, Object> project = requireProjectRead(projectId);
        if (current().isViewer()) throw BusinessException.forbidden("只读账号不能修改业务数据");
        if ("ARCHIVED".equals(project.get("status"))) throw BusinessException.conflict("项目已归档");
        return project;
    }

    public void requireProjectManager(long projectId) {
        requireProjectActive(projectId);
        if (current().isAdmin()) return;
        Map<String, Object> membership = membership(projectId, current().id());
        if (membership == null || !"PM".equals(membership.get("projectRole"))) throw BusinessException.forbidden("仅项目经理可操作");
    }

    public void requireModuleManager(long moduleId) {
        Map<String, Object> module = db.one("SELECT id,project_id,owner_id FROM modules WHERE id=:id", Map.of("id", moduleId), "模块不存在");
        long projectId = number(module.get("projectId"));
        requireProjectActive(projectId);
        if (current().isAdmin() || isPm(projectId) || number(module.get("ownerId")) == current().id()) return;
        throw BusinessException.forbidden("无权管理该模块");
    }

    public void requireNodeManager(long nodeId) {
        Map<String, Object> node = db.one("SELECT n.id,n.project_id,n.owner_id,m.owner_id module_owner_id FROM nodes n LEFT JOIN modules m ON m.id=n.module_id WHERE n.id=:id",
                Map.of("id", nodeId), "工作项不存在");
        long projectId = number(node.get("projectId"));
        requireProjectActive(projectId);
        long userId = current().id();
        if (current().isAdmin() || isPm(projectId) || number(node.get("ownerId")) == userId) return;
        Object moduleOwner = node.get("moduleOwnerId");
        if (moduleOwner != null && number(moduleOwner) == userId) return;
        throw BusinessException.forbidden("无权管理工作项");
    }

    public void requireTaskManager(long taskId, boolean allowAssignee) {
        Map<String, Object> task = db.one("SELECT t.id,t.assignee_id,n.id node_id,n.project_id,n.owner_id node_owner_id,m.owner_id module_owner_id FROM tasks t JOIN nodes n ON n.id=t.node_id LEFT JOIN modules m ON m.id=n.module_id WHERE t.id=:id",
                Map.of("id", taskId), "任务不存在");
        long projectId = number(task.get("projectId"));
        requireProjectActive(projectId);
        long userId = current().id();
        if (current().isAdmin() || isPm(projectId) || number(task.get("nodeOwnerId")) == userId
                || (allowAssignee && number(task.get("assigneeId")) == userId)) return;
        Object moduleOwner = task.get("moduleOwnerId");
        if (moduleOwner != null && number(moduleOwner) == userId) return;
        throw BusinessException.forbidden("无权管理该任务");
    }

    public long projectForNode(long nodeId) {
        return number(db.one("SELECT project_id FROM nodes WHERE id=:id", Map.of("id", nodeId), "工作项不存在").get("projectId"));
    }

    public long projectForTask(long taskId) {
        return number(db.one("SELECT n.project_id FROM tasks t JOIN nodes n ON n.id=t.node_id WHERE t.id=:id", Map.of("id", taskId), "任务不存在").get("projectId"));
    }

    public long projectForReport(long reportId) {
        return number(db.one("SELECT project_id FROM weekly_reports WHERE id=:id", Map.of("id", reportId), "周报不存在").get("projectId"));
    }

    public boolean isPm(long projectId) {
        Map<String, Object> member = membership(projectId, current().id());
        return member != null && "PM".equals(member.get("projectRole"));
    }

    public void requireAssignable(long projectId, long userId) {
        Map<String, Object> row = db.optional("SELECT u.id,u.active,u.global_role FROM users u JOIN project_members pm ON pm.user_id=u.id WHERE pm.project_id=:project AND u.id=:user",
                Map.of("project", projectId, "user", userId));
        if (row == null || !truthy(row.get("active")) || "VIEWER".equals(row.get("globalRole"))) {
            throw BusinessException.badRequest("负责人必须是有效且非只读的项目成员");
        }
    }

    private Map<String, Object> project(long projectId) {
        return db.one("SELECT id,name,code,status FROM projects WHERE id=:id", Map.of("id", projectId), "项目不存在");
    }

    private Map<String, Object> membership(long projectId, long userId) {
        return db.optional("SELECT id,project_role FROM project_members WHERE project_id=:project AND user_id=:user",
                Map.of("project", projectId, "user", userId));
    }

    public static long number(Object value) { return ((Number) value).longValue(); }
    private static boolean truthy(Object value) { return value instanceof Boolean b ? b : ((Number) value).intValue() != 0; }
}
