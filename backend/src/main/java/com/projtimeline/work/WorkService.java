package com.projtimeline.work;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projtimeline.common.BusinessException;
import com.projtimeline.data.DataAccess;
import com.projtimeline.project.ProjectService;
import com.projtimeline.security.PermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static com.projtimeline.common.RequestValues.*;
import static com.projtimeline.security.PermissionService.number;

@Service
public class WorkService {
    private final DataAccess db;
    private final PermissionService permissions;
    private final ProjectService projects;
    private final ObjectMapper json;
    private final Clock clock;

    public WorkService(DataAccess db, PermissionService permissions, ProjectService projects, ObjectMapper json, Clock clock) {
        this.db = db; this.permissions = permissions; this.projects = projects; this.json = json; this.clock = clock;
    }

    public Map<String, Object> node(long id) {
        long projectId = permissions.projectForNode(id); permissions.requireProjectRead(projectId);
        Map<String, Object> node = db.one("SELECT n.*,u.name owner_name,m.name module_name FROM nodes n JOIN users u ON u.id=n.owner_id JOIN modules m ON m.id=n.module_id WHERE n.id=:id", Map.of("id", id), "节点不存在");
        projects.decorateNode(node);
        node.put("tasks", tasks(id));
        node.put("logs", logs(id));
        return node;
    }

    @Transactional
    public Map<String, Object> createNode(long projectId, Map<String, Object> body) {
        long moduleId = requiredLong(body, "moduleId");
        Map<String, Object> module = db.one("SELECT id,project_id,owner_id FROM modules WHERE id=:id", Map.of("id", moduleId), "模块不存在");
        if (number(module.get("projectId")) != projectId) throw BusinessException.badRequest("模块不属于该项目");
        permissions.requireModuleManager(moduleId);
        LocalDate start = requiredDate(body, "planStart"), end = requiredDate(body, "planEnd"); checkRange(start, end);
        Long requestedOwner = longValue(body, "ownerId"); long owner = requestedOwner == null ? number(module.get("ownerId")) : requestedOwner;
        permissions.requireAssignable(projectId, owner);
        Map<String, Object> values = new HashMap<>(); values.put("project", projectId); values.put("module", moduleId); values.put("name", requiredText(body, "name"));
        values.put("description", text(body, "description")); values.put("start", start); values.put("end", end); values.put("owner", owner);
        values.put("sort", Optional.ofNullable(intValue(body, "sortOrder")).orElse(0)); values.put("creator", permissions.current().id());
        long id = db.insert("INSERT INTO nodes(project_id,module_id,name,description,plan_start,plan_end,owner_id,sort_order,created_by) VALUES(:project,:module,:name,:description,:start,:end,:owner,:sort,:creator)", values);
        log(id, "CREATE", "创建节点", null, Map.of("name", values.get("name")));
        return node(id);
    }

    @Transactional
    public Map<String, Object> updateNode(long id, Map<String, Object> body) {
        permissions.requireNodeManager(id);
        Map<String, Object> before = db.one("SELECT * FROM nodes WHERE id=:id", Map.of("id", id), "节点不存在");
        LocalDate start = requiredDate(body, "planStart"), end = requiredDate(body, "planEnd"); checkRange(start, end);
        long owner = Optional.ofNullable(longValue(body, "ownerId")).orElse(number(before.get("ownerId")));
        permissions.requireAssignable(number(before.get("projectId")), owner);
        Map<String, Object> values = new HashMap<>(); values.put("id", id); values.put("name", requiredText(body, "name")); values.put("description", text(body, "description"));
        values.put("start", start); values.put("end", end); values.put("owner", owner); values.put("sort", Optional.ofNullable(intValue(body, "sortOrder")).orElse(((Number) before.get("sortOrder")).intValue()));
        db.update("UPDATE nodes SET name=:name,description=:description,plan_start=:start,plan_end=:end,owner_id=:owner,sort_order=:sort WHERE id=:id", values);
        log(id, "RESCHEDULE", "更新节点", before, values);
        return node(id);
    }

    @Transactional
    public Map<String, Object> changeNodeStatus(long id, String target) {
        permissions.requireNodeManager(id);
        Map<String, Object> before = db.one("SELECT * FROM nodes WHERE id=:id FOR UPDATE", Map.of("id", id), "节点不存在");
        String current = Objects.toString(before.get("status"));
        Set<String> allowed = switch (current) {
            case "NOT_STARTED" -> Set.of("IN_PROGRESS", "CANCELLED");
            case "IN_PROGRESS" -> Set.of("DONE", "CANCELLED");
            default -> Set.of();
        };
        if (!allowed.contains(target)) throw BusinessException.conflict("非法节点状态流转");
        LocalDate today = LocalDate.now(clock);
        Map<String, Object> values = new HashMap<>(); values.put("id", id); values.put("status", target);
        values.put("actualStart", "IN_PROGRESS".equals(target) && before.get("actualStart") == null ? today : before.get("actualStart"));
        values.put("actualEnd", "DONE".equals(target) ? today : null); values.put("progress", "DONE".equals(target) ? 100 : before.get("progress"));
        db.update("UPDATE nodes SET status=:status,actual_start=:actualStart,actual_end=:actualEnd,progress=:progress WHERE id=:id", values);
        String action = "DONE".equals(target) ? "COMPLETE" : "CANCELLED".equals(target) ? "CANCEL" : "STATUS_CHANGE";
        log(id, action, "节点状态变更为 " + target, Map.of("status", current), Map.of("status", target));
        if ("DONE".equals(target)) notifyPm(number(before.get("projectId")), "NODE_DONE", "节点已完成", "/nodes/" + id);
        return node(id);
    }

    @Transactional
    public Map<String, Object> progress(long id, int progress) {
        permissions.requireNodeManager(id); if (progress < 0 || progress > 100) throw BusinessException.badRequest("progress 必须为 0-100");
        Map<String, Object> before = db.one("SELECT progress,status FROM nodes WHERE id=:id", Map.of("id", id), "节点不存在");
        if ("DONE".equals(before.get("status")) && progress != 100) throw BusinessException.conflict("已完成节点进度必须为 100");
        db.update("UPDATE nodes SET progress=:progress WHERE id=:id", Map.of("id", id, "progress", progress));
        log(id, "PROGRESS_UPDATE", "更新节点进度", Map.of("progress", before.get("progress")), Map.of("progress", progress));
        return node(id);
    }

    @Transactional
    public Map<String, Object> block(long id, String reason) {
        permissions.requireNodeManager(id); if (reason == null || reason.isBlank()) throw BusinessException.badRequest("reason 不能为空");
        Map<String, Object> before = db.one("SELECT project_id,module_id,blocked,blocked_reason FROM nodes WHERE id=:id", Map.of("id", id), "节点不存在");
        db.update("UPDATE nodes SET blocked=1,blocked_reason=:reason WHERE id=:id", Map.of("id", id, "reason", reason.trim()));
        log(id, "BLOCK", "节点标记受阻", Map.of("blocked", before.get("blocked")), Map.of("blocked", true, "reason", reason.trim()));
        notifyManagers(number(before.get("projectId")), number(before.get("moduleId")), "BLOCK_ALERT", "节点受阻", "/nodes/" + id);
        return node(id);
    }

    @Transactional
    public Map<String, Object> unblock(long id) {
        permissions.requireNodeManager(id); Map<String, Object> before = db.one("SELECT blocked,blocked_reason FROM nodes WHERE id=:id", Map.of("id", id), "节点不存在");
        db.update("UPDATE nodes SET blocked=0,blocked_reason=NULL WHERE id=:id", Map.of("id", id));
        log(id, "UNBLOCK", "解除节点受阻", before, Map.of("blocked", false)); return node(id);
    }

    @Transactional
    public Map<String, Object> overdueReason(long id, String reason) {
        permissions.requireNodeManager(id); if (reason == null || reason.isBlank()) throw BusinessException.badRequest("reason 不能为空");
        Map<String, Object> before = db.one("SELECT project_id,module_id,status,plan_end,overdue_reason FROM nodes WHERE id=:id", Map.of("id", id), "节点不存在");
        LocalDate end = (LocalDate) before.get("planEnd"); String status = Objects.toString(before.get("status"));
        if (!(Set.of("NOT_STARTED", "IN_PROGRESS").contains(status) && end.isBefore(LocalDate.now(clock)))) throw BusinessException.badRequest("节点当前未延期");
        if (reason.trim().equals(before.get("overdueReason"))) return node(id);
        db.update("UPDATE nodes SET overdue_reason=:reason WHERE id=:id", Map.of("id", id, "reason", reason.trim()));
        log(id, "OVERDUE_REASON", "提交延期原因", map("reason", before.get("overdueReason")), Map.of("reason", reason.trim()));
        notifyManagers(number(before.get("projectId")), number(before.get("moduleId")), "OVERDUE_ALERT", "节点延期说明已更新", "/nodes/" + id);
        return node(id);
    }

    public List<Map<String, Object>> logs(long nodeId) {
        permissions.requireProjectRead(permissions.projectForNode(nodeId));
        List<Map<String, Object>> rows = db.list("SELECT l.*,u.name user_name FROM node_logs l LEFT JOIN users u ON u.id=l.user_id WHERE l.node_id=:node ORDER BY l.created_at DESC,l.id DESC", Map.of("node", nodeId));
        rows.forEach(row -> { row.put("beforeValue", db.parseJson(row.get("beforeValue"))); row.put("afterValue", db.parseJson(row.get("afterValue"))); });
        return rows;
    }

    public List<Map<String, Object>> tasks(long nodeId) {
        permissions.requireProjectRead(permissions.projectForNode(nodeId));
        return db.list("SELECT t.*,u.name assignee_name,(SELECT SUM(w.days) FROM task_work_logs w WHERE w.task_id=t.id) actual_days FROM tasks t JOIN users u ON u.id=t.assignee_id WHERE t.node_id=:node ORDER BY FIELD(t.status,'DOING','TODO','DONE'),t.due_date,t.id", Map.of("node", nodeId));
    }

    @Transactional
    public Map<String, Object> createTask(long nodeId, Map<String, Object> body) {
        permissions.requireNodeManager(nodeId); Map<String, Object> node = db.one("SELECT id,project_id,owner_id FROM nodes WHERE id=:id", Map.of("id", nodeId), "节点不存在");
        long assignee = Optional.ofNullable(longValue(body, "assigneeId")).orElse(number(node.get("ownerId"))); permissions.requireAssignable(number(node.get("projectId")), assignee);
        BigDecimal estimate = decimal(body, "estimateDays"); validateDays(estimate, true);
        Map<String, Object> values = new HashMap<>(); values.put("node", nodeId); values.put("name", requiredText(body, "name")); values.put("description", text(body, "description"));
        values.put("assignee", assignee); values.put("due", date(body, "dueDate")); values.put("estimate", estimate); values.put("creator", permissions.current().id());
        long id = db.insert("INSERT INTO tasks(node_id,name,description,assignee_id,due_date,estimate_days,created_by) VALUES(:node,:name,:description,:assignee,:due,:estimate,:creator)", values);
        log(nodeId, "TASK_ADD", "创建任务", null, Map.of("taskId", id, "name", values.get("name")));
        notifyUser(assignee, "TASK_ASSIGN", "新任务已指派", "/nodes/" + nodeId);
        return task(id);
    }

    @Transactional
    public Map<String, Object> updateTask(long id, Map<String, Object> body) {
        permissions.requireTaskManager(id, false); Map<String, Object> before = task(id); long projectId = permissions.projectForTask(id);
        long assignee = Optional.ofNullable(longValue(body, "assigneeId")).orElse(number(before.get("assigneeId"))); permissions.requireAssignable(projectId, assignee);
        BigDecimal estimate = decimal(body, "estimateDays"); validateDays(estimate, true);
        Map<String, Object> values = new HashMap<>(); values.put("id", id); values.put("name", requiredText(body, "name")); values.put("description", text(body, "description"));
        values.put("assignee", assignee); values.put("due", date(body, "dueDate")); values.put("estimate", estimate);
        db.update("UPDATE tasks SET name=:name,description=:description,assignee_id=:assignee,due_date=:due,estimate_days=:estimate WHERE id=:id", values);
        long nodeId = number(before.get("nodeId")); log(nodeId, "TASK_STATUS", "更新任务", before, values);
        if (assignee != number(before.get("assigneeId"))) notifyUser(assignee, "TASK_ASSIGN", "任务已改派", "/nodes/" + nodeId);
        return task(id);
    }

    @Transactional
    public Map<String, Object> taskStatus(long id, String status) {
        permissions.requireTaskManager(id, true); if (!Set.of("TODO", "DOING", "DONE").contains(status)) throw BusinessException.badRequest("任务状态无效");
        Map<String, Object> before = task(id); LocalDateTime doneAt = "DONE".equals(status) ? LocalDateTime.now(clock) : null;
        db.update("UPDATE tasks SET status=:status,done_at=:done WHERE id=:id", map("status", status, "done", doneAt, "id", id));
        log(number(before.get("nodeId")), "TASK_STATUS", "任务状态变更", Map.of("status", before.get("status")), Map.of("status", status)); return task(id);
    }

    public List<Map<String, Object>> workLogs(long taskId) {
        permissions.requireProjectRead(permissions.projectForTask(taskId));
        return db.list("SELECT w.*,u.name user_name FROM task_work_logs w JOIN users u ON u.id=w.user_id WHERE w.task_id=:task ORDER BY w.work_date DESC,w.id DESC", Map.of("task", taskId));
    }

    @Transactional
    public Map<String, Object> addWorkLog(long taskId, Map<String, Object> body) {
        permissions.requireTaskManager(taskId, true); Map<String, Object> task = task(taskId);
        Long requestedUser = longValue(body, "userId"); long userId = requestedUser == null ? permissions.current().id() : requestedUser;
        if (userId != permissions.current().id()) permissions.requireTaskManager(taskId, false);
        permissions.requireAssignable(permissions.projectForTask(taskId), userId);
        LocalDate workDate = requiredDate(body, "workDate"); if (workDate.isAfter(LocalDate.now(clock))) throw BusinessException.badRequest("工时日期不能晚于今天");
        BigDecimal days = decimal(body, "days"); validateDays(days, false);
        long id = db.insert("INSERT INTO task_work_logs(task_id,user_id,work_date,days,note) VALUES(:task,:user,:date,:days,:note)", map("task", taskId, "user", userId, "date", workDate, "days", days, "note", text(body, "note")));
        log(number(task.get("nodeId")), "WORK_LOG_ADD", "新增工时", null, Map.of("workLogId", id, "workDate", workDate, "days", days)); return workLog(id);
    }

    @Transactional
    public Map<String, Object> updateWorkLog(long id, Map<String, Object> body) {
        Map<String, Object> before = workLog(id); long taskId = number(before.get("taskId"));
        if (number(before.get("userId")) != permissions.current().id()) permissions.requireTaskManager(taskId, false); else permissions.requireTaskManager(taskId, true);
        LocalDate workDate = requiredDate(body, "workDate"); if (workDate.isAfter(LocalDate.now(clock))) throw BusinessException.badRequest("工时日期不能晚于今天");
        BigDecimal days = decimal(body, "days"); validateDays(days, false);
        db.update("UPDATE task_work_logs SET work_date=:date,days=:days,note=:note WHERE id=:id", map("id", id, "date", workDate, "days", days, "note", text(body, "note")));
        Map<String, Object> task = task(taskId); log(number(task.get("nodeId")), "WORK_LOG_UPDATE", "更正工时", before, workLog(id)); return workLog(id);
    }

    @Transactional
    public void deleteWorkLog(long id) {
        Map<String, Object> before = workLog(id); long taskId = number(before.get("taskId"));
        if (number(before.get("userId")) != permissions.current().id()) permissions.requireTaskManager(taskId, false); else permissions.requireTaskManager(taskId, true);
        Map<String, Object> task = task(taskId); db.update("DELETE FROM task_work_logs WHERE id=:id", Map.of("id", id));
        log(number(task.get("nodeId")), "WORK_LOG_DELETE", "删除工时", before, null);
    }

    private Map<String, Object> task(long id) { return db.one("SELECT t.*,u.name assignee_name,(SELECT SUM(days) FROM task_work_logs WHERE task_id=t.id) actual_days FROM tasks t JOIN users u ON u.id=t.assignee_id WHERE t.id=:id", Map.of("id", id), "任务不存在"); }
    private Map<String, Object> workLog(long id) { return db.one("SELECT * FROM task_work_logs WHERE id=:id", Map.of("id", id), "工时记录不存在"); }

    private void log(long nodeId, String action, String detail, Object before, Object after) {
        db.insert("INSERT INTO node_logs(node_id,user_id,action,detail,before_value,after_value) VALUES(:node,:user,:action,:detail,:before,:after)",
                map("node", nodeId, "user", permissions.current().id(), "action", action, "detail", detail, "before", json(before), "after", json(after)));
    }

    private String json(Object value) {
        if (value == null) return null;
        try { return json.writeValueAsString(value); } catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }

    private void notifyManagers(long projectId, long moduleId, String type, String title, String link) {
        Set<Long> users = new LinkedHashSet<>();
        db.list("SELECT user_id FROM project_members WHERE project_id=:project AND project_role='PM'", Map.of("project", projectId)).forEach(r -> users.add(number(r.get("userId"))));
        users.add(number(db.one("SELECT owner_id FROM modules WHERE id=:id", Map.of("id", moduleId), "模块不存在").get("ownerId")));
        users.forEach(id -> notifyUser(id, type, title, link));
    }

    private void notifyPm(long projectId, String type, String title, String link) {
        db.list("SELECT user_id FROM project_members WHERE project_id=:project AND project_role='PM'", Map.of("project", projectId)).forEach(r -> notifyUser(number(r.get("userId")), type, title, link));
    }

    private void notifyUser(long userId, String type, String title, String link) {
        db.insert("INSERT INTO notifications(user_id,type,title,link) VALUES(:user,:type,:title,:link)", Map.of("user", userId, "type", type, "title", title, "link", link));
    }

    private static long requiredLong(Map<String, Object> body, String key) { Long value = longValue(body, key); if (value == null) throw BusinessException.badRequest(key + " 不能为空"); return value; }
    private static LocalDate requiredDate(Map<String, Object> body, String key) { LocalDate value = date(body, key); if (value == null) throw BusinessException.badRequest(key + " 不能为空"); return value; }
    private static void checkRange(LocalDate start, LocalDate end) { if (start.isAfter(end)) throw BusinessException.badRequest("计划开始日期不能晚于结束日期"); }
    private static void validateDays(BigDecimal value, boolean allowZero) {
        if (value == null) return; int cmp = value.compareTo(BigDecimal.ZERO); if ((allowZero && cmp < 0) || (!allowZero && cmp <= 0) || value.scale() > 1) throw BusinessException.badRequest("人天必须为 0.1 精度的有效数值");
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new HashMap<>(); for (int i = 0; i < values.length; i += 2) result.put(values[i].toString(), values[i + 1]); return result;
    }
}
