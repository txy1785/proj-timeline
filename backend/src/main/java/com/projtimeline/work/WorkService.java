package com.projtimeline.work;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projtimeline.common.BusinessException;
import com.projtimeline.common.DeliverableRules;
import com.projtimeline.data.DataAccess;
import com.projtimeline.notification.NotificationService;
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
    /** 交付物列表与单行共用列清单，避免两处 SELECT 漂移；调用方需保证 d（交付物）别名可用。 */
    private static final String DELIVERABLE_SQL = """
            SELECT d.id, d.node_id, d.project_id, d.name, d.status, d.owner_id, u.name owner_name, d.remark,
                   d.attachment_id, a.title attachment_title, a.original_name attachment_original_name,
                   CASE WHEN a.id IS NOT NULL AND a.deleted=1 THEN 1 ELSE 0 END attachment_deleted,
                   d.sort_order, d.created_at, d.updated_at
            FROM node_deliverables d
            LEFT JOIN users u ON u.id=d.owner_id
            LEFT JOIN attachments a ON a.id=d.attachment_id
            """;

    private final DataAccess db;
    private final PermissionService permissions;
    private final ProjectService projects;
    private final NotificationService notifications;
    private final ObjectMapper json;
    private final Clock clock;

    public WorkService(DataAccess db, PermissionService permissions, ProjectService projects, NotificationService notifications, ObjectMapper json, Clock clock) {
        this.db = db; this.permissions = permissions; this.projects = projects; this.notifications = notifications; this.json = json; this.clock = clock;
    }

    public Map<String, Object> node(long id) {
        long projectId = permissions.projectForNode(id); permissions.requireProjectRead(projectId);
        Map<String, Object> node = db.one("""
                SELECT n.*, u.name owner_name, m.name module_name, m.owner_id module_owner_id, p.status project_status,p.name project_name,
                       (SELECT COUNT(*) FROM tasks t WHERE t.node_id=n.id) total_tasks,
                       (SELECT COUNT(*) FROM tasks t WHERE t.node_id=n.id AND t.status='DONE') done_tasks,
                       (SELECT COUNT(*) FROM node_deliverables d WHERE d.node_id=n.id) deliverable_total,
                       (SELECT COUNT(*) FROM node_deliverables d WHERE d.node_id=n.id AND d.status='PROVIDED') deliverable_provided
                FROM nodes n JOIN users u ON u.id=n.owner_id LEFT JOIN modules m ON m.id=n.module_id
                JOIN projects p ON p.id=n.project_id
                WHERE n.id=:id
                """, Map.of("id", id), "工作项不存在");
        projects.decorateNode(node);
        node.put("tasks", tasks(id));
        node.put("deliverables", listDeliverables(id));
        node.put("logs", logs(id));
        return node;
    }

    @Transactional
    public Map<String, Object> createNode(long projectId, Map<String, Object> body) {
        Long requestedModule = longValue(body, "moduleId");
        boolean projectLevel = requestedModule == null || requestedModule == 0;
        Long requestedOwner = positiveLong(body, "ownerId");
        long owner;
        String type = workItemType(body, "FEATURE");
        if (projectLevel) {
            permissions.requireProjectManager(projectId);
            if (requestedOwner != null) {
                owner = requestedOwner;
            } else {
                // ADMIN 不一定是项目成员，默认负责人取当前 PM，避免 requireAssignable 失败
                Map<String, Object> pm = db.optional("SELECT user_id FROM project_members WHERE project_id=:project AND project_role='PM' LIMIT 1", Map.of("project", projectId));
                owner = pm == null ? permissions.current().id() : number(pm.get("userId"));
            }
        } else {
            Map<String, Object> module = db.one("SELECT id,project_id,owner_id FROM modules WHERE id=:id", Map.of("id", requestedModule), "模块不存在");
            if (number(module.get("projectId")) != projectId) throw BusinessException.badRequest("模块不属于该项目");
            permissions.requireModuleManager(requestedModule);
            owner = requestedOwner == null ? number(module.get("ownerId")) : requestedOwner;
        }
        LocalDate start = requiredDate(body, "planStart"), end = requiredDate(body, "planEnd"); checkRange(start, end);
        permissions.requireAssignable(projectId, owner);
        Map<String, Object> values = new HashMap<>(); values.put("project", projectId);
        values.put("module", projectLevel ? null : requestedModule); values.put("type", type); values.put("name", nameText(body));
        values.put("description", text(body, "description")); values.put("start", start); values.put("end", end); values.put("owner", owner);
        values.put("sort", Optional.ofNullable(intValue(body, "sortOrder")).orElse(0)); values.put("creator", permissions.current().id());
        long id = db.insert("INSERT INTO nodes(project_id,module_id,type,name,description,plan_start,plan_end,owner_id,sort_order,created_by) VALUES(:project,:module,:type,:name,:description,:start,:end,:owner,:sort,:creator)", values);
        log(id, "CREATE", projectLevel ? "创建项目级工作项" : "创建工作项", null, map("name", values.get("name"), "type", type));
        return node(id);
    }

    @Transactional
    public Map<String, Object> updateNode(long id, Map<String, Object> body) {
        permissions.requireNodeManager(id);
        // 只取业务字段：progress 列已弃用，不应再进入变更日志的 before_value
        Map<String, Object> before = db.one("SELECT name,description,type,plan_start,plan_end,owner_id,sort_order,project_id FROM nodes WHERE id=:id", Map.of("id", id), "工作项不存在");
        LocalDate start = requiredDate(body, "planStart"), end = requiredDate(body, "planEnd"); checkRange(start, end);
        long owner = Optional.ofNullable(positiveLong(body, "ownerId")).orElse(number(before.get("ownerId")));
        permissions.requireAssignable(number(before.get("projectId")), owner);
        String type = workItemType(body, Objects.toString(before.get("type")));
        Map<String, Object> values = new HashMap<>(); values.put("id", id); values.put("name", nameText(body)); values.put("description", text(body, "description"));
        values.put("type", type); values.put("start", start); values.put("end", end); values.put("owner", owner); values.put("sort", Optional.ofNullable(intValue(body, "sortOrder")).orElse(((Number) before.get("sortOrder")).intValue()));
        db.update("UPDATE nodes SET name=:name,description=:description,type=:type,plan_start=:start,plan_end=:end,owner_id=:owner,sort_order=:sort WHERE id=:id", values);
        log(id, "RESCHEDULE", "更新工作项", before, values);
        return node(id);
    }

    @Transactional
    public Map<String, Object> changeNodeStatus(long id, String target) {
        permissions.requireNodeManager(id);
        Map<String, Object> before = db.one("SELECT * FROM nodes WHERE id=:id FOR UPDATE", Map.of("id", id), "工作项不存在");
        String current = Objects.toString(before.get("status"));
        Set<String> allowed = switch (current) {
            case "NOT_STARTED" -> Set.of("IN_PROGRESS", "CANCELLED");
            case "IN_PROGRESS" -> Set.of("DONE", "CANCELLED");
            default -> Set.of();
        };
        if (!allowed.contains(target)) throw BusinessException.conflict("非法工作项状态流转");
        // 进度由任务完成率派生：工作项关闭前必须先把任务做完，否则 DONE 与进度会自相矛盾
        if ("DONE".equals(target)) {
            // 用锁定读取未完成任务：REPEATABLE READ 下普通 COUNT 可能仍读旧快照，看不到刚提交的新任务
            long openTasks = db.list("SELECT id FROM tasks WHERE node_id=:id AND status<>'DONE' FOR UPDATE", Map.of("id", id)).size();
            if (openTasks > 0) throw BusinessException.conflict("还有 " + openTasks + " 个任务未完成，全部完成后才能完成工作项");
        }
        LocalDate today = LocalDate.now(clock);
        Map<String, Object> values = new HashMap<>(); values.put("id", id); values.put("status", target);
        values.put("actualStart", "IN_PROGRESS".equals(target) && before.get("actualStart") == null ? today : before.get("actualStart"));
        values.put("actualEnd", "DONE".equals(target) ? today : null);
        db.update("UPDATE nodes SET status=:status,actual_start=:actualStart,actual_end=:actualEnd WHERE id=:id", values);
        String action = "DONE".equals(target) ? "COMPLETE" : "CANCELLED".equals(target) ? "CANCEL" : "STATUS_CHANGE";
        log(id, action, "工作项状态变更为 " + target, Map.of("status", current), Map.of("status", target));
        if ("DONE".equals(target)) notifyPm(number(before.get("projectId")), "NODE_DONE", "工作项已完成", "/nodes/" + id);
        return node(id);
    }

    @Transactional
    public Map<String, Object> block(long id, String reason) {
        permissions.requireNodeManager(id); if (reason == null || reason.isBlank()) throw BusinessException.badRequest("reason 不能为空");
        Map<String, Object> before = db.one("SELECT project_id,module_id,blocked,blocked_reason FROM nodes WHERE id=:id", Map.of("id", id), "工作项不存在");
        db.update("UPDATE nodes SET blocked=1,blocked_reason=:reason WHERE id=:id", Map.of("id", id, "reason", reason.trim()));
        log(id, "BLOCK", "工作项标记受阻", Map.of("blocked", before.get("blocked")), Map.of("blocked", true, "reason", reason.trim()));
        notifyManagers(number(before.get("projectId")), longOrNull(before.get("moduleId")), "BLOCK_ALERT", "工作项受阻", "/nodes/" + id);
        return node(id);
    }

    @Transactional
    public Map<String, Object> unblock(long id) {
        permissions.requireNodeManager(id); Map<String, Object> before = db.one("SELECT blocked,blocked_reason FROM nodes WHERE id=:id", Map.of("id", id), "工作项不存在");
        db.update("UPDATE nodes SET blocked=0,blocked_reason=NULL WHERE id=:id", Map.of("id", id));
        log(id, "UNBLOCK", "解除工作项受阻", before, Map.of("blocked", false)); return node(id);
    }

    @Transactional
    public Map<String, Object> overdueReason(long id, String reason) {
        permissions.requireNodeManager(id); if (reason == null || reason.isBlank()) throw BusinessException.badRequest("reason 不能为空");
        Map<String, Object> before = db.one("SELECT project_id,module_id,status,plan_end,overdue_reason FROM nodes WHERE id=:id", Map.of("id", id), "工作项不存在");
        LocalDate end = (LocalDate) before.get("planEnd"); String status = Objects.toString(before.get("status"));
        if (!(Set.of("NOT_STARTED", "IN_PROGRESS").contains(status) && end.isBefore(LocalDate.now(clock)))) throw BusinessException.badRequest("工作项当前未延期");
        if (reason.trim().equals(before.get("overdueReason"))) return node(id);
        db.update("UPDATE nodes SET overdue_reason=:reason WHERE id=:id", Map.of("id", id, "reason", reason.trim()));
        log(id, "OVERDUE_REASON", "提交延期原因", map("reason", before.get("overdueReason")), Map.of("reason", reason.trim()));
        notifyManagers(number(before.get("projectId")), longOrNull(before.get("moduleId")), "OVERDUE_ALERT", "工作项延期说明已更新", "/nodes/" + id);
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
        return db.list("SELECT t.*,u.name assignee_name,r.name related_node_name,(SELECT SUM(w.days) FROM task_work_logs w WHERE w.task_id=t.id) actual_days,(SELECT COUNT(*) FROM task_notes n WHERE n.task_id=t.id) note_count FROM tasks t JOIN users u ON u.id=t.assignee_id LEFT JOIN nodes r ON r.id=t.related_node_id WHERE t.node_id=:node ORDER BY FIELD(t.status,'DOING','TODO','DONE'),t.due_date,t.id", Map.of("node", nodeId));
    }

    /**
     * 工作项交付物清单：回答"这个工作项要交什么、还缺什么、文件在哪"。
     * 文件继续复用 attachments 的工作项级文件，清单项只可选关联其中一个，不新建文件体系。
     */
    public List<Map<String, Object>> deliverables(long nodeId) {
        permissions.requireProjectRead(permissions.projectForNode(nodeId));
        return listDeliverables(nodeId);
    }

    private List<Map<String, Object>> listDeliverables(long nodeId) {
        return db.list(DELIVERABLE_SQL + " WHERE d.node_id=:node ORDER BY d.sort_order, d.id", Map.of("node", nodeId));
    }

    @Transactional
    public Map<String, Object> createDeliverable(long nodeId, Map<String, Object> body) {
        permissions.requireNodeManager(nodeId);
        // 与新增任务同一锁顺序：先锁工作项行，避免与"完成工作项"并发时给已关闭工作项补清单
        Map<String, Object> node = db.one("SELECT id,project_id,status FROM nodes WHERE id=:id FOR UPDATE", Map.of("id", nodeId), "工作项不存在");
        requireNodeOpen(node, "已完成或已取消的工作项不能新增交付物");
        long projectId = number(node.get("projectId"));
        Map<String, Object> values = deliverableValues(body, projectId, nodeId, null);
        values.put("node", nodeId); values.put("project", projectId); values.put("creator", permissions.current().id());
        long id = db.insert("""
                INSERT INTO node_deliverables(node_id,project_id,name,status,owner_id,remark,attachment_id,sort_order,created_by)
                VALUES(:node,:project,:name,:status,:owner,:remark,:attachment,:sort,:creator)
                """, values);
        log(nodeId, "DELIVERABLE_ADD", "新增交付物", null, Map.of("deliverableId", id, "name", values.get("name")));
        return deliverable(id);
    }

    @Transactional
    public Map<String, Object> updateDeliverable(long id, Map<String, Object> body) {
        // 先按 id 取所属工作项做授权，再锁工作项与交付物行：避免用锁前快照判断"关闭工作项只允许改状态"
        long nodeId = nodeOfDeliverable(id);
        permissions.requireNodeManager(nodeId);
        Map<String, Object> node = db.one("SELECT id,project_id,status FROM nodes WHERE id=:id FOR UPDATE", Map.of("id", nodeId), "工作项不存在");
        lockDeliverable(id);
        Map<String, Object> before = deliverable(id);
        Map<String, Object> values = deliverableValues(body, number(node.get("projectId")), nodeId, before);
        // 已关闭工作项只允许补标记"已提供"（补资料是常态），改名、改派、换文件一律拒绝，避免事后改写交付事实
        if (Set.of("DONE", "CANCELLED").contains(Objects.toString(node.get("status"))) && !DeliverableRules.onlyStatusChanged(before, values)) {
            throw BusinessException.conflict("工作项已完成或已取消，只能更新交付物状态");
        }
        // 整表回传但没有任何变化时不写日志，与任务状态接口的幂等口径一致
        if (DeliverableRules.unchanged(before, values)) return before;
        values.put("id", id);
        // 可写列发生变化时，同步更新 DeliverableRules.onlyStatusChanged 的比较键，否则关闭工作项上会漏判新增列
        db.update("UPDATE node_deliverables SET name=:name,status=:status,owner_id=:owner,remark=:remark,attachment_id=:attachment,sort_order=:sort WHERE id=:id", values);
        log(nodeId, "DELIVERABLE_UPDATE", "更新交付物", Map.of("name", before.get("name"), "status", before.get("status")), Map.of("name", values.get("name"), "status", values.get("status")));
        return deliverable(id);
    }

    @Transactional
    public void deleteDeliverable(long id) {
        long nodeId = nodeOfDeliverable(id);
        permissions.requireNodeManager(nodeId);
        Map<String, Object> node = db.one("SELECT status FROM nodes WHERE id=:id FOR UPDATE", Map.of("id", nodeId), "工作项不存在");
        lockDeliverable(id);
        Map<String, Object> before = deliverable(id);
        requireNodeOpen(node, "已完成或已取消的工作项不能删除交付物");
        if (db.update("DELETE FROM node_deliverables WHERE id=:id", Map.of("id", id)) != 1) throw BusinessException.conflict("交付物已被删除");
        log(nodeId, "DELIVERABLE_DELETE", "删除交付物", Map.of("deliverableId", id, "name", before.get("name")), null);
    }

    @Transactional
    public Map<String, Object> createTask(long nodeId, Map<String, Object> body) {
        permissions.requireNodeManager(nodeId);
        // 锁工作项行，和完成工作项、任务状态变更串行，避免并发下给已完成工作项插入任务
        Map<String, Object> node = db.one("SELECT id,project_id,owner_id,status FROM nodes WHERE id=:id FOR UPDATE", Map.of("id", nodeId), "工作项不存在");
        requireNodeOpen(node, "已完成或已取消的工作项不能新增任务");
        long assignee = Optional.ofNullable(positiveLong(body, "assigneeId")).orElse(number(node.get("ownerId"))); permissions.requireAssignable(number(node.get("projectId")), assignee);
        BigDecimal estimate = decimal(body, "estimateDays"); validateDays(estimate, true);
        long projectId = number(node.get("projectId"));
        String kind = taskKind(body, "FEATURE");
        Long related = relatedNodeValue(body, projectId, null);
        Map<String, Object> values = new HashMap<>(); values.put("node", nodeId); values.put("name", nameText(body)); values.put("description", text(body, "description"));
        values.put("kind", kind); values.put("related", related);
        values.put("situation", detailText(body, "situation")); values.put("handling", detailText(body, "handling"));
        values.put("assignee", assignee); values.put("due", date(body, "dueDate")); values.put("estimate", estimate); values.put("creator", permissions.current().id());
        long id = db.insert("INSERT INTO tasks(node_id,name,description,kind,related_node_id,situation,handling,assignee_id,due_date,estimate_days,created_by) VALUES(:node,:name,:description,:kind,:related,:situation,:handling,:assignee,:due,:estimate,:creator)", values);
        log(nodeId, "TASK_ADD", "创建任务", null, map("taskId", id, "name", values.get("name"), "kind", kind));
        notifyUser(assignee, "TASK_ASSIGN", "新任务已指派", "/nodes/" + nodeId + "?task=" + id);
        return task(id);
    }

    @Transactional
    public Map<String, Object> updateTask(long id, Map<String, Object> body) {
        permissions.requireTaskManager(id, false); Map<String, Object> before = task(id); long projectId = permissions.projectForTask(id);
        long nodeId = number(before.get("nodeId"));
        Map<String, Object> node = db.one("SELECT status FROM nodes WHERE id=:id FOR UPDATE", Map.of("id", nodeId), "工作项不存在");
        requireNodeOpen(node, "已完成或已取消工作项的任务不能修改");
        long assignee = Optional.ofNullable(positiveLong(body, "assigneeId")).orElse(number(before.get("assigneeId"))); permissions.requireAssignable(projectId, assignee);
        BigDecimal estimate = decimal(body, "estimateDays"); validateDays(estimate, true);
        String kind = taskKind(body, Objects.toString(before.get("kind")));
        Long related = relatedNodeValue(body, projectId, before.get("relatedNodeId"));
        Map<String, Object> values = new HashMap<>(); values.put("id", id); values.put("name", nameText(body)); values.put("description", text(body, "description"));
        values.put("kind", kind); values.put("related", related);
        values.put("situation", detailText(body, "situation")); values.put("handling", detailText(body, "handling"));
        values.put("assignee", assignee); values.put("due", date(body, "dueDate")); values.put("estimate", estimate);
        db.update("UPDATE tasks SET name=:name,description=:description,kind=:kind,related_node_id=:related,situation=:situation,handling=:handling,assignee_id=:assignee,due_date=:due,estimate_days=:estimate WHERE id=:id", values);
        log(nodeId, "TASK_UPDATE", "更新任务", before, values);
        if (assignee != number(before.get("assigneeId"))) notifyUser(assignee, "TASK_ASSIGN", "任务已改派", "/nodes/" + nodeId + "?task=" + id);
        return task(id);
    }

    @Transactional
    public void deleteTask(long id) {
        permissions.requireTaskManager(id, false);
        Map<String, Object> task = db.one("SELECT id,node_id,name FROM tasks WHERE id=:id", Map.of("id", id), "任务不存在");
        long nodeId = number(task.get("nodeId"));
        Map<String, Object> node = db.one("SELECT status FROM nodes WHERE id=:id FOR UPDATE", Map.of("id", nodeId), "工作项不存在");
        requireNodeOpen(node, "已完成或已取消工作项的任务不能删除");
        task = db.one("SELECT id,node_id,name FROM tasks WHERE id=:id FOR UPDATE", Map.of("id", id), "任务不存在");
        // 任务附件软删除保留磁盘文件；记录与工时随任务一起清理，避免外键孤儿
        db.update("UPDATE attachments SET deleted=1 WHERE owner_type='TASK' AND owner_id=:id", Map.of("id", id));
        db.update("DELETE FROM task_notes WHERE task_id=:id", Map.of("id", id));
        db.update("DELETE FROM task_work_logs WHERE task_id=:id", Map.of("id", id));
        if (db.update("DELETE FROM tasks WHERE id=:id", Map.of("id", id)) != 1) throw BusinessException.conflict("任务已被删除");
        log(nodeId, "TASK_DELETE", "删除任务", Map.of("taskId", id, "name", task.get("name")), null);
    }

    @Transactional
    public Map<String, Object> reportBug(long projectId, Map<String, Object> body) {
        permissions.requireProjectActive(projectId);
        String name = requiredText(body, "name");
        if (name.length() > 200) throw BusinessException.badRequest("缺陷标题不能超过 200 字");
        String description = detailText(body, "description");
        String situation = detailText(body, "situation");
        Long relatedRequested = longValue(body, "relatedNodeId");
        Long relatedId = relatedRequested != null && relatedRequested > 0 ? relatedRequested : null;
        Map<String, Object> target = resolveBugTarget(projectId, relatedRequested, true);
        if (target == null) throw BusinessException.conflict("维护负责人尚未开启缺陷修复工作项");
        long nodeId = number(target.get("id"));
        long assignee = number(target.get("ownerId"));
        permissions.requireAssignable(projectId, assignee);
        Map<String, Object> values = new HashMap<>();
        values.put("node", nodeId); values.put("name", name); values.put("description", description);
        values.put("situation", situation); values.put("related", relatedId); values.put("assignee", assignee); values.put("creator", permissions.current().id());
        long id = db.insert("""
                INSERT INTO tasks(node_id,name,description,kind,related_node_id,situation,assignee_id,status,created_by)
                VALUES(:node,:name,:description,'BUG',:related,:situation,:assignee,'TODO',:creator)
                """, values);
        log(nodeId, "TASK_ADD", "提交缺陷", null, map("taskId", id, "name", name, "relatedNodeId", relatedId));
        notifications.notify(assignee, "TASK_ASSIGN", "新缺陷已指派", "/nodes/" + nodeId + "?task=" + id);
        Map<String, Object> result = new LinkedHashMap<>(task(id));
        result.put("routedNodeId", nodeId);
        result.put("routedNodeName", target.get("name"));
        result.put("routedModuleName", target.get("moduleName"));
        return result;
    }

    /** Read-only preflight. The POST always reruns the same resolver under node locks. */
    @Transactional(readOnly = true)
    public Map<String, Object> bugRouting(long projectId, Long relatedNodeId) {
        permissions.requireProjectActive(projectId);
        Map<String, Object> target = resolveBugTarget(projectId, relatedNodeId, false);
        if (target == null) return Map.of("canSubmit", false, "message", "暂无接收工作项，请联系项目经理开启缺陷修复");
        long owner = number(target.get("ownerId"));
        try { permissions.requireAssignable(projectId, owner); }
        catch (BusinessException ex) {
            if (ex.code() != 4000) throw ex;
            return Map.of("canSubmit", false, "message", "接收负责人已失效，请联系项目经理调整工作项负责人");
        }
        String ownerName = Objects.toString(db.one("SELECT name FROM users WHERE id=:id", Map.of("id", owner), "负责人不存在").get("name"), "");
        return Map.of("canSubmit", true, "nodeId", target.get("id"), "nodeName", target.get("name"),
                "ownerId", owner, "ownerName", ownerName, "message", "将提交给" + ownerName + " · " + target.get("name"));
    }

    /** Single routing rule shared by preflight and submission; false must never acquire write locks. */
    private Map<String, Object> resolveBugTarget(long projectId, Long relatedNodeId, boolean lock) {
        if (relatedNodeId != null && relatedNodeId < 0) throw BusinessException.badRequest("relatedNodeId 必须是正整数");
        String suffix = lock ? " FOR UPDATE" : "";
        if (relatedNodeId != null && relatedNodeId > 0) {
            Map<String, Object> related = db.one("""
                    SELECT n.id,n.project_id,n.status,n.owner_id,n.name,m.name module_name
                    FROM nodes n LEFT JOIN modules m ON m.id=n.module_id WHERE n.id=:id
                    """ + suffix, Map.of("id", relatedNodeId), "关联工作项不存在");
            if (number(related.get("projectId")) != projectId) throw BusinessException.badRequest("关联工作项不属于该项目");
            if ("IN_PROGRESS".equals(Objects.toString(related.get("status")))) return related;
        }
        return db.optional("""
                SELECT n.id,n.name,n.owner_id,n.status,m.name module_name
                FROM nodes n LEFT JOIN modules m ON m.id=n.module_id
                WHERE n.project_id=:project AND n.module_id IS NULL AND n.type='BUGFIX' AND n.status='IN_PROGRESS'
                ORDER BY n.plan_start DESC,n.id DESC LIMIT 1
                """ + suffix, Map.of("project", projectId));
    }

    @Transactional
    public Map<String, Object> taskStatus(long id, String status, String expectedStatus) {
        permissions.requireTaskManager(id, true); if (!Set.of("TODO", "DOING", "DONE").contains(status)) throw BusinessException.badRequest("任务状态无效");
        Map<String, Object> before = task(id);
        // 与工作项完成/新增任务串行：先锁工作项行，再对任务行做当前读，避免用旧快照判断状态
        long nodeId = number(before.get("nodeId"));
        Map<String, Object> node = db.one("SELECT status FROM nodes WHERE id=:id FOR UPDATE", Map.of("id", nodeId), "工作项不存在");
        String nodeStatus = Objects.toString(node.get("status"));
        String currentStatus = String.valueOf(db.one("SELECT status FROM tasks WHERE id=:id FOR UPDATE", Map.of("id", id), "任务不存在").get("status"));
        if (Set.of("DONE", "CANCELLED").contains(nodeStatus)) {
            // 这里不用 requireNodeOpen：关闭工作项上重复提交同一状态要幂等返回，只有真的改状态才拒绝
            if (status.equals(currentStatus)) return task(id); // 幂等：关闭工作项上重复提交同一状态不写日志
            throw BusinessException.conflict("工作项已完成或已取消，任务状态不能再变更");
        }
        // 列表里的快捷操作带着点击时的状态一起提交，避免并发/多标签页下把已变化的任务改错
        if (expectedStatus != null && !expectedStatus.isBlank() && !expectedStatus.equals(currentStatus)) {
            throw BusinessException.conflict("任务状态已变化，请刷新后重试");
        }
        LocalDateTime doneAt = "DONE".equals(status) ? LocalDateTime.now(clock) : null;
        db.update("UPDATE tasks SET status=:status,done_at=:done WHERE id=:id", map("status", status, "done", doneAt, "id", id));
        log(number(before.get("nodeId")), "TASK_STATUS", "任务状态变更", Map.of("status", currentStatus), Map.of("status", status)); return task(id);
    }

    public List<Map<String, Object>> workLogs(long taskId) {
        permissions.requireProjectRead(permissions.projectForTask(taskId));
        return db.list("SELECT w.*,u.name user_name FROM task_work_logs w JOIN users u ON u.id=w.user_id WHERE w.task_id=:task ORDER BY w.work_date DESC,w.id DESC", Map.of("task", taskId));
    }

    /** 任务进展记录：按时间倒序，用于记录完成思路与结果，也可当备注用。 */
    public List<Map<String, Object>> taskNotes(long taskId) {
        permissions.requireProjectRead(permissions.projectForTask(taskId));
        return db.list("""
                SELECT n.id, n.task_id, n.author_id, u.name author_name, n.content, n.created_at
                FROM task_notes n JOIN users u ON u.id = n.author_id
                WHERE n.task_id = :task
                ORDER BY n.created_at DESC, n.id DESC
                """, Map.of("task", taskId));
    }

    @Transactional
    public Map<String, Object> addTaskNote(long taskId, Map<String, Object> body) {
        permissions.requireTaskManager(taskId, true);
        String content = requiredText(body, "content");
        if (content.length() > 2000) throw BusinessException.badRequest("记录不能超过 2000 字");
        // 与删任务串行：先锁工作项和任务行，避免记录插入时任务已被删除导致外键 500
        long nodeId = number(db.one("SELECT node_id FROM tasks WHERE id=:id", Map.of("id", taskId), "任务不存在").get("nodeId"));
        lockNode(nodeId);
        db.one("SELECT id FROM tasks WHERE id=:id FOR UPDATE", Map.of("id", taskId), "任务不存在");
        // 记录本身带作者与时间，不重复写 node_logs（其 action 枚举未包含记录事件）；时间用注入的 Clock，避免依赖数据库时区
        long id = db.insert("INSERT INTO task_notes(task_id,author_id,content,created_at) VALUES(:task,:author,:content,:created)",
                map("task", taskId, "author", permissions.current().id(), "content", content,
                        "created", LocalDateTime.now(clock)));
        return db.one("SELECT n.*,u.name author_name FROM task_notes n JOIN users u ON u.id=n.author_id WHERE n.id=:id", Map.of("id", id), "记录不存在");
    }

    @Transactional
    public void deleteTaskNote(long id) {
        Map<String, Object> note = db.one("SELECT id,task_id,author_id FROM task_notes WHERE id=:id", Map.of("id", id), "记录不存在");
        long taskId = number(note.get("taskId"));
        // 归档项目与 VIEWER 一律拒绝；作者身份只决定能否越过管理者校验，不决定是否检查项目状态
        permissions.requireProjectActive(permissions.projectForTask(taskId));
        if (number(note.get("authorId")) != permissions.current().id()) permissions.requireTaskManager(taskId, false);
        long nodeId = number(db.one("SELECT node_id FROM tasks WHERE id=:id", Map.of("id", taskId), "任务不存在").get("nodeId"));
        lockNode(nodeId);
        if (db.update("DELETE FROM task_notes WHERE id=:id", Map.of("id", id)) != 1) throw BusinessException.notFound("记录不存在");
    }

    @Transactional
    public Map<String, Object> addWorkLog(long taskId, Map<String, Object> body) {
        permissions.requireTaskManager(taskId, true); Map<String, Object> task = task(taskId);
        lockNode(number(task.get("nodeId")));
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
        Map<String, Object> task = task(taskId); lockNode(number(task.get("nodeId")));
        LocalDate workDate = requiredDate(body, "workDate"); if (workDate.isAfter(LocalDate.now(clock))) throw BusinessException.badRequest("工时日期不能晚于今天");
        BigDecimal days = decimal(body, "days"); validateDays(days, false);
        db.update("UPDATE task_work_logs SET work_date=:date,days=:days,note=:note WHERE id=:id", map("id", id, "date", workDate, "days", days, "note", text(body, "note")));
        log(number(task.get("nodeId")), "WORK_LOG_UPDATE", "更正工时", before, workLog(id)); return workLog(id);
    }

    @Transactional
    public void deleteWorkLog(long id) {
        Map<String, Object> before = workLog(id); long taskId = number(before.get("taskId"));
        if (number(before.get("userId")) != permissions.current().id()) permissions.requireTaskManager(taskId, false); else permissions.requireTaskManager(taskId, true);
        Map<String, Object> task = task(taskId); lockNode(number(task.get("nodeId"))); db.update("DELETE FROM task_work_logs WHERE id=:id", Map.of("id", id));
        log(number(task.get("nodeId")), "WORK_LOG_DELETE", "删除工时", before, null);
    }

    private Map<String, Object> task(long id) { return db.one("SELECT t.*,u.name assignee_name,r.name related_node_name,(SELECT SUM(days) FROM task_work_logs WHERE task_id=t.id) actual_days FROM tasks t JOIN users u ON u.id=t.assignee_id LEFT JOIN nodes r ON r.id=t.related_node_id WHERE t.id=:id", Map.of("id", id), "任务不存在"); }

    private Map<String, Object> deliverable(long id) {
        return db.one(DELIVERABLE_SQL + " WHERE d.id=:id", Map.of("id", id), "交付物不存在");
    }

    /** 先按 id 取所属工作项，用于在锁行前完成授权；读不到按 4004 处理。 */
    private long nodeOfDeliverable(long id) {
        return number(db.one("SELECT node_id FROM node_deliverables WHERE id=:id", Map.of("id", id), "交付物不存在").get("nodeId"));
    }

    /** 与工作项锁保持同一顺序：先锁工作项，再锁交付物行，最后读全量字段。 */
    private void lockDeliverable(long id) {
        db.one("SELECT id FROM node_deliverables WHERE id=:id FOR UPDATE", Map.of("id", id), "交付物不存在");
    }

    /** 新增（before 为 null）与编辑共用字段口径；编辑未传的字段保留原值，传 null 表示清空负责人或关联文件。 */
    private Map<String, Object> deliverableValues(Map<String, Object> body, long projectId, long nodeId, Map<String, Object> before) {
        String name = before == null || body.containsKey("name") ? requiredText(body, "name") : Objects.toString(before.get("name"));
        if (name.length() > 200) throw BusinessException.badRequest("交付物名称不能超过 200 字");
        String remark = before != null && !body.containsKey("remark") ? (String) before.get("remark") : text(body, "remark");
        if (remark != null && remark.length() > 500) throw BusinessException.badRequest("交付物备注不能超过 500 字");
        String status = DeliverableRules.status(body.get("status"), before == null ? DeliverableRules.WANTED : Objects.toString(before.get("status")));
        Long owner = before != null && !body.containsKey("ownerId") ? longOrNull(before.get("ownerId")) : longValue(body, "ownerId");
        // 只校验真正改动的负责人：成员被停用后，纯状态更新不应被 400 拦住（该由关闭工作项规则给出 409）
        if (owner != null && (before == null || !Objects.equals(longOrNull(before.get("ownerId")), owner))) permissions.requireAssignable(projectId, owner);
        Long attachment = before != null && !body.containsKey("attachmentId") ? longOrNull(before.get("attachmentId")) : longValue(body, "attachmentId");
        // 同理：关联文件被软删除后，未改动的引用不能挡住"标为已提供"
        if (attachment != null && (before == null || !Objects.equals(longOrNull(before.get("attachmentId")), attachment))) requireNodeAttachment(nodeId, attachment);
        Integer sort = intValue(body, "sortOrder");
        Map<String, Object> values = new HashMap<>();
        values.put("name", name); values.put("status", status); values.put("owner", owner); values.put("remark", remark);
        values.put("attachment", attachment); values.put("sort", sort != null ? sort : before == null ? 0 : ((Number) before.get("sortOrder")).intValue());
        return values;
    }

    private void requireNodeAttachment(long nodeId, long attachmentId) {
        Map<String, Object> row = db.optional("SELECT id FROM attachments WHERE id=:id AND owner_type='NODE' AND owner_id=:node AND deleted=0",
                Map.of("id", attachmentId, "node", nodeId));
        if (row == null) throw BusinessException.badRequest("只能关联本工作项已上传的文件");
    }

    private static void requireNodeOpen(Map<String, Object> node, String message) {
        if (Set.of("DONE", "CANCELLED").contains(Objects.toString(node.get("status")))) throw BusinessException.conflict(message);
    }

    private static Long longOrNull(Object value) { return value == null ? null : ((Number) value).longValue(); }

    /** 统一锁顺序：先锁工作项再写任务/工时，避免与工作项完成路径形成反向锁。 */
    private void lockNode(long nodeId) { db.one("SELECT id FROM nodes WHERE id=:id FOR UPDATE", Map.of("id", nodeId), "工作项不存在"); }
    private Map<String, Object> workLog(long id) { return db.one("SELECT * FROM task_work_logs WHERE id=:id", Map.of("id", id), "工时记录不存在"); }

    private void log(long nodeId, String action, String detail, Object before, Object after) {
        db.insert("INSERT INTO node_logs(node_id,user_id,action,detail,before_value,after_value) VALUES(:node,:user,:action,:detail,:before,:after)",
                map("node", nodeId, "user", permissions.current().id(), "action", action, "detail", detail, "before", json(before), "after", json(after)));
    }

    private String json(Object value) {
        if (value == null) return null;
        try { return json.writeValueAsString(value); } catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }

    private void notifyManagers(long projectId, Long moduleId, String type, String title, String link) {
        Set<Long> users = new LinkedHashSet<>();
        db.list("SELECT user_id FROM project_members WHERE project_id=:project AND project_role='PM'", Map.of("project", projectId)).forEach(r -> users.add(number(r.get("userId"))));
        if (moduleId != null) users.add(number(db.one("SELECT owner_id FROM modules WHERE id=:id", Map.of("id", moduleId), "模块不存在").get("ownerId")));
        users.forEach(id -> notifyUser(id, type, title, link));
    }

    private void notifyPm(long projectId, String type, String title, String link) {
        notifications.notifyProjectPm(projectId, type, title, link);
    }

    private void notifyUser(long userId, String type, String title, String link) {
        notifications.notify(userId, type, title, link);
    }

    private static String workItemType(Map<String, Object> body, String fallback) {
        String value = text(body, "type");
        if (value == null) return fallback;
        value = value.toUpperCase(Locale.ROOT);
        if (!Set.of("FEATURE", "TEST", "MAINTENANCE", "BUGFIX", "OTHER").contains(value)) throw BusinessException.badRequest("工作项类型无效");
        return value;
    }

    private static String taskKind(Map<String, Object> body, String fallback) {
        String value = text(body, "kind");
        if (value == null) return fallback;
        value = value.toUpperCase(Locale.ROOT);
        if (!Set.of("FEATURE", "FIX", "BUG", "TEST", "OTHER").contains(value)) throw BusinessException.badRequest("任务类型无效");
        return value;
    }

    /** 关联工作项可选：传 0/null 表示清空，未传则保留原值；只允许关联同一项目的工作项。 */
    private Long relatedNodeValue(Map<String, Object> body, long projectId, Object beforeValue) {
        Long related;
        if (body.containsKey("relatedNodeId")) {
            Long raw = longValue(body, "relatedNodeId");
            related = raw == null || raw == 0 ? null : raw;
        } else {
            related = longOrNull(beforeValue);
        }
        if (related != null) {
            Map<String, Object> row = db.one("SELECT project_id FROM nodes WHERE id=:id", Map.of("id", related), "关联工作项不存在");
            if (number(row.get("projectId")) != projectId) throw BusinessException.badRequest("关联工作项不属于该项目");
        }
        return related;
    }

    private static String nameText(Map<String, Object> body) {
        String name = requiredText(body, "name");
        if (name.length() > 200) throw BusinessException.badRequest("名称不能超过 200 字");
        return name;
    }

    private static Long positiveLong(Map<String, Object> body, String key) {
        Long value = longValue(body, key);
        return value == null || value == 0 ? null : value;
    }

    private static String detailText(Map<String, Object> body, String key) {
        String value = text(body, key);
        if (value != null && value.length() > 2000) throw BusinessException.badRequest(key + " 不能超过 2000 字");
        return value;
    }

    private static LocalDate requiredDate(Map<String, Object> body, String key) { LocalDate value = date(body, key); if (value == null) throw BusinessException.badRequest(key + " 不能为空"); return value; }
    private static void checkRange(LocalDate start, LocalDate end) { if (start.isAfter(end)) throw BusinessException.badRequest("计划开始日期不能晚于结束日期"); }
    private static void validateDays(BigDecimal value, boolean allowZero) {
        if (value == null) return; int cmp = value.compareTo(BigDecimal.ZERO); if ((allowZero && cmp < 0) || (!allowZero && cmp <= 0) || value.scale() > 1) throw BusinessException.badRequest("人天必须为 0.1 精度的有效数值");
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new HashMap<>(); for (int i = 0; i < values.length; i += 2) result.put(values[i].toString(), values[i + 1]); return result;
    }
}
