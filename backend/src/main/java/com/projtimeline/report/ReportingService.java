package com.projtimeline.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projtimeline.common.BusinessException;
import com.projtimeline.common.BusinessCalendar;
import com.projtimeline.common.PageResponse;
import com.projtimeline.data.DataAccess;
import com.projtimeline.notification.NotificationService;
import com.projtimeline.security.CurrentUser;
import com.projtimeline.security.PermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static com.projtimeline.security.PermissionService.number;

@Service
public class ReportingService {
    private final DataAccess db;
    private final PermissionService permissions;
    private final NotificationService notifications;
    private final ObjectMapper json;
    private final Clock clock;

    public ReportingService(DataAccess db, PermissionService permissions, NotificationService notifications, ObjectMapper json, Clock clock) {
        this.db = db; this.permissions = permissions; this.notifications = notifications; this.json = json; this.clock = clock;
    }

    public Map<String, Object> workload(LocalDate anyDate) {
        LocalDate[] week = week(anyDate == null ? LocalDate.now(clock) : anyDate);
        CurrentUser current = permissions.current();
        String projectFilter = permissions.readableProjectFilter();
        String peopleFilter = current.isAdmin() || current.isViewer() ? "" : """
                 AND (u.id=:current OR EXISTS(SELECT 1 FROM project_members person_member
                     JOIN projects p ON p.id=person_member.project_id WHERE person_member.user_id=u.id
                """ + projectFilter + "))";
        Map<String, Object> params = new HashMap<>(); params.put("current", current.id()); params.put("start", week[0]); params.put("end", week[1]); params.put("today", LocalDate.now(clock));
        List<Map<String, Object>> rows = db.list("""
                SELECT u.id,u.name,u.username,
                  (SELECT COUNT(*) FROM nodes n JOIN projects p ON p.id=n.project_id WHERE n.owner_id=u.id AND n.status='IN_PROGRESS' %s) active_node_count,
                  (SELECT COUNT(*) FROM nodes n JOIN projects p ON p.id=n.project_id WHERE n.owner_id=u.id AND n.status IN('NOT_STARTED','IN_PROGRESS') AND n.plan_end<:today %s) overdue_node_count,
                  (SELECT COUNT(*) FROM tasks t JOIN nodes n ON n.id=t.node_id JOIN projects p ON p.id=n.project_id WHERE t.assignee_id=u.id AND t.status IN('TODO','DOING') AND n.status<>'CANCELLED' %s) open_task_count,
                  (SELECT COUNT(*) FROM tasks t JOIN nodes n ON n.id=t.node_id JOIN projects p ON p.id=n.project_id WHERE t.assignee_id=u.id AND t.status<>'DONE' AND t.due_date BETWEEN :start AND :end AND n.status<>'CANCELLED' %s) week_due_count,
                  (SELECT COALESCE(SUM(t.estimate_days),0) FROM tasks t JOIN nodes n ON n.id=t.node_id JOIN projects p ON p.id=n.project_id WHERE t.assignee_id=u.id AND t.status<>'DONE' AND n.status<>'CANCELLED' %s) active_estimate_days,
                  (SELECT COALESCE(SUM(t.estimate_days),0) FROM tasks t JOIN nodes n ON n.id=t.node_id JOIN projects p ON p.id=n.project_id WHERE t.assignee_id=u.id AND t.due_date BETWEEN :start AND :end AND n.status<>'CANCELLED' %s) week_due_estimate_days
                FROM users u WHERE u.active=1 AND u.global_role<>'VIEWER' %s ORDER BY u.name
                """.formatted(projectFilter, projectFilter, projectFilter, projectFilter, projectFilter, projectFilter, peopleFilter), params);
        return map("weekStart", week[0], "weekEnd", week[1], "people", rows);
    }

    public PageResponse<Map<String, Object>> notifications(int page, int size) {
        page = Math.max(1, page); size = Math.min(100, Math.max(1, size)); long user = permissions.current().id();
        Map<String, Object> params = Map.of("user", user, "offset", (page - 1) * size, "size", size);
        long total = db.count("SELECT COUNT(*) FROM notifications WHERE user_id=:user", params);
        List<Map<String, Object>> rows = db.list("SELECT * FROM notifications WHERE user_id=:user ORDER BY read_at IS NULL DESC,created_at DESC,id DESC LIMIT :offset,:size", params);
        return new PageResponse<>(rows, total, page, size);
    }

    @Transactional
    public void readNotification(long id) {
        if (db.update("UPDATE notifications SET read_at=COALESCE(read_at,NOW()) WHERE id=:id AND user_id=:user", Map.of("id", id, "user", permissions.current().id())) != 1) {
            throw BusinessException.notFound("通知不存在");
        }
    }

    @Transactional
    public void readAllNotifications() { db.update("UPDATE notifications SET read_at=NOW() WHERE user_id=:user AND read_at IS NULL", Map.of("user", permissions.current().id())); }

    public Map<String, Object> report(long projectId, LocalDate anyDate) {
        permissions.requireProjectRead(projectId); LocalDate[] week = week(anyDate == null ? LocalDate.now(clock) : anyDate);
        Map<String, Object> row = db.optional("SELECT * FROM weekly_reports WHERE project_id=:project AND week_start=:start", Map.of("project", projectId, "start", week[0]));
        if (row != null && "PUBLISHED".equals(row.get("status"))) { row.put("content", db.parseJson(row.get("content"))); return row; }
        Map<String, Object> result = row == null ? new LinkedHashMap<>() : row;
        result.put("projectId", projectId); result.put("weekStart", week[0]); result.put("weekEnd", week[1]); result.put("status", "DRAFT");
        result.put("content", aggregate(projectId, week[0], week[1])); return result;
    }

    public List<Map<String, Object>> reportHistory(long projectId) {
        permissions.requireProjectRead(projectId); List<Map<String, Object>> rows = db.list("SELECT * FROM weekly_reports WHERE project_id=:project AND status='PUBLISHED' ORDER BY week_start DESC", Map.of("project", projectId));
        rows.forEach(r -> r.put("content", db.parseJson(r.get("content")))); return rows;
    }

    @Transactional
    public Map<String, Object> createDraft(long projectId, LocalDate weekStart) {
        requireReportManager(projectId); if (weekStart == null || weekStart.getDayOfWeek() != DayOfWeek.MONDAY) throw BusinessException.badRequest("weekStart 必须是周一");
        Map<String, Object> existing = db.optional("SELECT * FROM weekly_reports WHERE project_id=:project AND week_start=:start", Map.of("project", projectId, "start", weekStart));
        if (existing != null) { if ("PUBLISHED".equals(existing.get("status"))) throw BusinessException.conflict("该周周报已发布"); return report(projectId, weekStart); }
        db.insert("INSERT INTO weekly_reports(project_id,week_start,week_end,status,created_by) VALUES(:project,:start,:end,'DRAFT',:user)", Map.of("project", projectId, "start", weekStart, "end", weekStart.plusDays(6), "user", permissions.current().id()));
        return report(projectId, weekStart);
    }

    @Transactional
    public Map<String, Object> updateNote(long reportId, String note) {
        long projectId = permissions.projectForReport(reportId); requireReportManager(projectId);
        if (db.update("UPDATE weekly_reports SET note=:note WHERE id=:id AND status='DRAFT'", nullableMap("note", note, "id", reportId)) != 1) throw BusinessException.conflict("仅草稿周报可编辑");
        Map<String, Object> row = db.one("SELECT week_start FROM weekly_reports WHERE id=:id", Map.of("id", reportId), "周报不存在"); return report(projectId, (LocalDate) row.get("weekStart"));
    }

    @Transactional
    public Map<String, Object> publish(long reportId) {
        long projectId = permissions.projectForReport(reportId); requireReportManager(projectId);
        Map<String, Object> row = db.one("SELECT * FROM weekly_reports WHERE id=:id FOR UPDATE", Map.of("id", reportId), "周报不存在");
        if ("PUBLISHED".equals(row.get("status"))) { row.put("content", db.parseJson(row.get("content"))); return row; }
        LocalDate start = (LocalDate) row.get("weekStart"), end = (LocalDate) row.get("weekEnd"); String content = toJson(aggregate(projectId, start, end));
        db.update("UPDATE weekly_reports SET content=:content,status='PUBLISHED',published_at=NOW() WHERE id=:id AND status='DRAFT'", Map.of("content", content, "id", reportId));
        List<Map<String, Object>> members = db.list("SELECT DISTINCT user_id FROM project_members WHERE project_id=:project", Map.of("project", projectId));
        String link = "/projects/" + projectId + "/reports?week=" + start;
        for (Map<String, Object> member : members) notifications.notify(number(member.get("userId")), "WEEKLY_PUBLISHED", "项目周报已发布", link);
        return report(projectId, start);
    }

    private Map<String, Object> aggregate(long projectId, LocalDate start, LocalDate end) {
        LocalDateTime startTime = start.atStartOfDay(), endExclusive = end.plusDays(1).atStartOfDay();
        Map<String, Object> params = Map.of("project", projectId, "start", start, "end", end, "startTime", startTime, "endTime", endExclusive, "nextEnd", end.plusDays(7), "today", LocalDate.now(clock));
        List<Map<String, Object>> completed = db.list("SELECT id,name,actual_end FROM nodes WHERE project_id=:project AND actual_end BETWEEN :start AND :end ORDER BY actual_end", params);
        List<Map<String, Object>> exceptions = db.list("SELECT id,name,status,plan_end,blocked,blocked_reason,overdue_reason FROM nodes WHERE project_id=:project AND (blocked=1 OR (status IN('NOT_STARTED','IN_PROGRESS') AND plan_end<:today)) ORDER BY plan_end", params);
        List<Map<String, Object>> nextDue = db.list("SELECT id,name,plan_end,status FROM nodes WHERE project_id=:project AND status IN('NOT_STARTED','IN_PROGRESS') AND plan_end>:end AND plan_end<=:nextEnd ORDER BY plan_end", params);
        List<Map<String, Object>> newNodes = db.list("SELECT id,name,created_at FROM nodes WHERE project_id=:project AND created_at>=:startTime AND created_at<:endTime ORDER BY created_at", params);
        List<Map<String, Object>> newTasks = db.list("SELECT t.id,t.name,t.created_at FROM tasks t JOIN nodes n ON n.id=t.node_id WHERE n.project_id=:project AND t.created_at>=:startTime AND t.created_at<:endTime ORDER BY t.created_at", params);
        Object actual = db.scalar("SELECT COALESCE(SUM(w.days),0) FROM task_work_logs w JOIN tasks t ON t.id=w.task_id JOIN nodes n ON n.id=t.node_id WHERE n.project_id=:project AND w.work_date BETWEEN :start AND :end", params);
        Object planned = db.scalar("SELECT COALESCE(SUM(t.estimate_days),0) FROM tasks t JOIN nodes n ON n.id=t.node_id WHERE n.project_id=:project AND t.due_date BETWEEN :start AND :end AND n.status<>'CANCELLED'", params);
        return map("completedNodes", completed, "exceptionNodes", exceptions, "nextWeekDueNodes", nextDue, "newNodes", newNodes, "newTasks", newTasks, "actualDays", actual, "plannedDays", planned);
    }

    private void requireReportManager(long projectId) {
        permissions.requireProjectActive(projectId); if (!permissions.current().isAdmin() && !permissions.isPm(projectId)) throw BusinessException.forbidden("仅项目经理可维护周报");
    }

    private static LocalDate[] week(LocalDate date) { return BusinessCalendar.week(date); }
    private String toJson(Object value) { try { return json.writeValueAsString(value); } catch (JsonProcessingException ex) { throw new IllegalStateException(ex); } }
    private static Map<String, Object> map(Object... values) { Map<String, Object> result = new LinkedHashMap<>(); for (int i=0;i<values.length;i+=2) result.put(values[i].toString(), values[i+1]); return result; }
    private static Map<String, Object> nullableMap(Object... values) { return map(values); }
}
