package com.projtimeline.workspace;

import com.projtimeline.common.BusinessCalendar;
import com.projtimeline.common.BusinessException;
import com.projtimeline.common.PageResponse;
import com.projtimeline.data.DataAccess;
import com.projtimeline.project.ProjectService;
import com.projtimeline.security.CurrentUser;
import com.projtimeline.security.PermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Server-side, project-scoped reads for daily work. All methods are read-only. */
@Service
@Transactional(readOnly = true)
public class WorkspaceService {
    private static final int MANAGEMENT_LIMIT = 100;
    private static final String TASK_FROM = """
            FROM tasks t JOIN nodes n ON n.id=t.node_id JOIN projects p ON p.id=n.project_id
            JOIN users u ON u.id=t.assignee_id
            LEFT JOIN nodes related ON related.id=t.related_node_id AND related.project_id=p.id
            """;
    private static final String NODE_FROM = """
            FROM nodes n JOIN projects p ON p.id=n.project_id
            JOIN users u ON u.id=n.owner_id LEFT JOIN modules m ON m.id=n.module_id AND m.project_id=p.id
            """;
    private static final String NODE_COLUMNS = """
            SELECT n.*,p.name project_name,p.status project_status,u.name owner_name,
                   m.name module_name,m.owner_id module_owner_id,
                   (SELECT COUNT(*) FROM tasks nt WHERE nt.node_id=n.id) total_tasks,
                   (SELECT COUNT(*) FROM tasks nt WHERE nt.node_id=n.id AND nt.status='DONE') done_tasks,
                   (SELECT COUNT(*) FROM node_deliverables d WHERE d.node_id=n.id) deliverable_total,
                   (SELECT COUNT(*) FROM node_deliverables d WHERE d.node_id=n.id AND d.status='PROVIDED') deliverable_provided
            """;
    private final DataAccess db;
    private final PermissionService permissions;
    private final ProjectService projects;
    private final Clock clock;

    public WorkspaceService(DataAccess db, PermissionService permissions, ProjectService projects, Clock clock) {
        this.db = db; this.permissions = permissions; this.projects = projects; this.clock = clock;
    }

    public Map<String, Object> context() {
        long user = permissions.current().id();
        return Map.of("businessDate", LocalDate.now(clock), "timeZone", clock.getZone().getId(),
                "unreadCount", db.count("SELECT COUNT(*) FROM notifications WHERE user_id=:user AND read_at IS NULL", Map.of("user", user)));
    }

    public WorkspaceTaskPage tasks(String requestedScope, String requestedStatus, Long projectId, Long assigneeId,
                                   String query, int page, int size, LocalDate dueWeek) {
        String scope = option(requestedScope, "OPEN", Set.of("OPEN", "DONE", "HISTORY", "WORKLOAD", "WORKLOAD_PLAN"), "scope");
        String status = requestedStatus == null || requestedStatus.isBlank() ? null
                : option(requestedStatus, null, Set.of("TODO", "DOING", "DONE"), "status");
        positiveId(projectId, "projectId"); positiveId(assigneeId, "assigneeId");
        String search = query == null ? "" : query.trim();
        if (search.length() > 200) throw BusinessException.badRequest("搜索内容不能超过 200 字");
        boolean workload = "WORKLOAD".equals(scope) || "WORKLOAD_PLAN".equals(scope);
        if (dueWeek != null && !workload) throw BusinessException.badRequest("dueWeek 仅用于人员负载展开");
        if ("WORKLOAD_PLAN".equals(scope) && dueWeek == null) throw BusinessException.badRequest("本周计划展开必须指定 dueWeek");
        CurrentUser current = permissions.current();
        if (projectId != null) permissions.requireProjectRead(projectId);
        page = Math.max(1, page); size = Math.min(100, Math.max(1, size));
        LocalDate today = LocalDate.now(clock);
        Map<String, Object> params = new HashMap<>();
        params.put("current", current.id()); params.put("assignee", assigneeId == null ? current.id() : assigneeId);
        params.put("today", today); params.put("size", size); params.put("offset", ((long) page - 1) * size);
        StringBuilder base = new StringBuilder(" WHERE t.assignee_id=:assignee").append(permissions.readableProjectFilter());
        if (projectId != null) { base.append(" AND p.id=:project"); params.put("project", projectId); }
        if (!search.isEmpty()) {
            params.put("query", "%" + search.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
            base.append(" AND (t.name LIKE :query ESCAPE '!' OR n.name LIKE :query ESCAPE '!' OR p.name LIKE :query ESCAPE '!')");
        }
        String open = "p.status='ACTIVE' AND n.status IN('NOT_STARTED','IN_PROGRESS') AND t.status IN('TODO','DOING')";
        String done = "p.status='ACTIVE' AND n.status<>'CANCELLED' AND t.status='DONE'";
        if (workload) {
            // Match ReportingService.workload, including readable archived-project history.
            base.append(" AND n.status<>'CANCELLED'");
            open = "t.status IN('TODO','DOING')";
            done = "t.status='DONE'";
            if (dueWeek != null) {
                LocalDate[] week = BusinessCalendar.week(dueWeek);
                base.append(" AND t.due_date BETWEEN :weekStart AND :weekEnd");
                params.put("weekStart", week[0]); params.put("weekEnd", week[1]);
            }
        }
        Map<String, Object> totals = db.one("SELECT "
                + "COALESCE(SUM(CASE WHEN " + open + " THEN 1 ELSE 0 END),0) open_count,"
                + "COALESCE(SUM(CASE WHEN " + open + " AND t.status='DOING' THEN 1 ELSE 0 END),0) doing_count,"
                + "COALESCE(SUM(CASE WHEN " + open + " AND t.due_date<:today THEN 1 ELSE 0 END),0) overdue_count,"
                + "COALESCE(SUM(CASE WHEN " + done + " THEN 1 ELSE 0 END),0) done_count " + TASK_FROM + base,
                params, "任务统计不可用");
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("open", count(totals, "openCount")); counts.put("doing", count(totals, "doingCount"));
        counts.put("overdue", count(totals, "overdueCount")); counts.put("done", count(totals, "doneCount"));
        String scopeFilter = switch (scope) {
            case "OPEN" -> " AND " + open;
            case "DONE" -> " AND " + done;
            case "WORKLOAD" -> " AND t.status IN('TODO','DOING')";
            default -> "";
        };
        String where = base + scopeFilter;
        if (status != null) { where += " AND t.status=:taskStatus"; params.put("taskStatus", status); }
        long total = db.count("SELECT COUNT(*) " + TASK_FROM + where, params);
        List<Map<String, Object>> rows = db.list("""
                SELECT t.*,n.name node_name,p.id project_id,p.name project_name,
                       p.status project_status,n.status node_status,u.name assignee_name,related.name related_node_name,
                       (SELECT COUNT(*) FROM task_notes notes WHERE notes.task_id=t.id) note_count,
                       CASE WHEN p.status='ACTIVE' AND n.status IN('NOT_STARTED','IN_PROGRESS')
                                      AND t.status IN('TODO','DOING') AND t.due_date<:today THEN TRUE ELSE FALSE END overdue
                """ + TASK_FROM + where + """
                 ORDER BY CASE WHEN t.status<>'DONE' AND t.due_date<:today THEN 0
                               WHEN t.status<>'DONE' AND t.due_date=:today THEN 1
                               WHEN t.status<>'DONE' AND t.due_date IS NOT NULL THEN 2
                               WHEN t.status<>'DONE' THEN 3 ELSE 4 END,
                          t.due_date IS NULL,t.due_date,t.id
                 LIMIT :offset,:size
                """, params);
        rows.forEach(row -> row.put("overdue", truthy(row.get("overdue"))));
        return new WorkspaceTaskPage(rows, total, page, size, today, clock.getZone().getId(), counts);
    }

    public Map<String, Object> management() {
        CurrentUser current = permissions.current();
        if (current.isViewer()) return Map.of("nodes", List.of(), "requests", List.of(), "nodeTotal", 0L, "requestTotal", 0L);
        Map<String, Object> params = Map.of("current", current.id(), "today", LocalDate.now(clock), "limit", MANAGEMENT_LIMIT);
        String manager = current.isAdmin() ? "" : """
                 AND (n.owner_id=:current OR m.owner_id=:current OR EXISTS(
                     SELECT 1 FROM project_members pm WHERE pm.project_id=p.id AND pm.user_id=:current AND pm.project_role='PM'))
                """;
        String where = " WHERE p.status='ACTIVE' AND n.status IN('NOT_STARTED','IN_PROGRESS')"
                + " AND (n.blocked=1 OR n.plan_end<:today)" + permissions.readableProjectFilter() + manager;
        long nodeTotal = db.count("SELECT COUNT(*) " + NODE_FROM + where, params);
        List<Map<String, Object>> nodes = db.list(NODE_COLUMNS + NODE_FROM + where
                + " ORDER BY CASE WHEN n.plan_end<:today THEN 0 ELSE 1 END,n.plan_end,n.id LIMIT :limit", params);
        nodes.forEach(projects::decorateNode);
        String requestFrom = """
                FROM module_change_requests r JOIN projects p ON p.id=r.project_id
                JOIN modules m ON m.id=r.module_id AND m.project_id=p.id JOIN users requester ON requester.id=r.requested_by
                LEFT JOIN users owner_user ON owner_user.id=r.owner_id
                """;
        String requestWhere = " WHERE p.status='ACTIVE' AND r.status='PENDING'" + permissions.readableProjectFilter()
                + (current.isAdmin() ? "" : " AND EXISTS(SELECT 1 FROM project_members pm WHERE pm.project_id=p.id AND pm.user_id=:current AND pm.project_role='PM')");
        long requestTotal = db.count("SELECT COUNT(*) " + requestFrom + requestWhere, params);
        List<Map<String, Object>> requests = db.list("""
                SELECT r.*,p.name project_name,m.name module_name,m.owner_id module_owner_id,
                       requester.name requester_name,owner_user.name owner_name
                """ + requestFrom + requestWhere + " ORDER BY r.created_at,r.id LIMIT :limit", params);
        return Map.of("nodes", nodes, "requests", requests, "nodeTotal", nodeTotal, "requestTotal", requestTotal);
    }

    public PageResponse<Map<String, Object>> workItems(Long ownerId, String requestedScope, int page, int size) {
        positiveId(ownerId, "ownerId");
        String scope = option(requestedScope, "ACTIVE", Set.of("ACTIVE", "OVERDUE"), "scope");
        CurrentUser current = permissions.current();
        page = Math.max(1, page); size = Math.min(100, Math.max(1, size));
        Map<String, Object> params = Map.of("current", current.id(), "owner", ownerId == null ? current.id() : ownerId,
                "today", LocalDate.now(clock), "offset", ((long) page - 1) * size, "size", size);
        // Same owner/status/project scope as the corresponding workload count.
        String where = " WHERE n.owner_id=:owner" + permissions.readableProjectFilter()
                + ("ACTIVE".equals(scope) ? " AND n.status='IN_PROGRESS'"
                : " AND n.status IN('NOT_STARTED','IN_PROGRESS') AND n.plan_end<:today");
        long total = db.count("SELECT COUNT(*) " + NODE_FROM + where, params);
        List<Map<String, Object>> rows = db.list(NODE_COLUMNS + NODE_FROM + where + " ORDER BY n.plan_end,n.id LIMIT :offset,:size", params);
        rows.forEach(projects::decorateNode);
        return new PageResponse<>(rows, total, page, size);
    }

    private static String option(String value, String fallback, Set<String> allowed, String field) {
        String result = value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
        if (result == null || !allowed.contains(result)) throw BusinessException.badRequest(field + " 无效");
        return result;
    }

    private static void positiveId(Long value, String field) {
        if (value != null && value <= 0) throw BusinessException.badRequest(field + " 必须是正整数");
    }

    private static long count(Map<String, Object> values, String key) {
        Object value = values.get(key); return value == null ? 0 : ((Number) value).longValue();
    }

    private static boolean truthy(Object value) { return value instanceof Boolean flag ? flag : value instanceof Number number && number.intValue() != 0; }
}
