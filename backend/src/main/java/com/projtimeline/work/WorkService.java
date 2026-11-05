package com.projtimeline.work;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projtimeline.common.BusinessException;
import com.projtimeline.common.DeliverableRules;
import com.projtimeline.data.DataAccess;
import com.projtimeline.data.TaskSourceSql;
import com.projtimeline.notification.NotificationService;
import com.projtimeline.project.ProjectService;
import com.projtimeline.security.PermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
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
    /**
     * 交付物列表与单行共用列清单，避免两处 SELECT 漂移；调用方需保证 d（交付物）别名可用。
     * 关联文件不在这里 JOIN：一条交付记录可关联多个文件，JOIN 会把交付物行放大成多行。
     */
    private static final String DELIVERABLE_SQL = """
            SELECT d.id, d.node_id, d.project_id, d.name, d.status, d.owner_id, u.name owner_name, d.remark,
                   d.sort_order, d.created_at, d.updated_at
            FROM node_deliverables d
            LEFT JOIN users u ON u.id=d.owner_id
            """;
    /**
     * 交付物关联文件列清单（V16 起一条交付记录可关联多个文件）；调用方补 WHERE 与 ORDER BY，
     * 主查询保持一行一条交付物，关联文件由这次额外查询取出后在 Java 里按 deliverable_id 装配。
     */
    private static final String DELIVERABLE_ATTACHMENT_SQL = """
            SELECT l.deliverable_id, a.id, a.title, a.original_name, a.content_type, a.size_bytes, a.version, a.deleted
            FROM node_deliverable_attachments l
            JOIN attachments a ON a.id=l.attachment_id
            """;
    /** 一条交付记录最多关联的文件数，防止误传整目录把清单撑爆。 */
    private static final int MAX_DELIVERABLE_ATTACHMENTS = 20;
    /** 新建工作项时一次性能约定的交付要求条数，防止把整份需求清单塞进创建接口。 */
    private static final int MAX_NODE_REQUIREMENTS = 20;
    private static final String UNASSIGNED_BUG_TARGET = "关联工作项未指派负责人，请指定Bug接收人，或联系项目经理先指派负责人";
    /** 进展记录列表与单行共用列清单；调用方需保证 n（task_notes）/u（作者）/e（最后编辑人）别名可用。 */
    private static final String NOTE_SELECT = """
            SELECT n.id,n.task_id,n.title,n.author_id,u.name author_name,n.content,n.created_at,
                   n.updated_by,n.updated_at,e.name updated_by_name
            FROM task_notes n
            JOIN users u ON u.id=n.author_id
            LEFT JOIN users e ON e.id=n.updated_by
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
                FROM nodes n LEFT JOIN users u ON u.id=n.owner_id LEFT JOIN modules m ON m.id=n.module_id
                JOIN projects p ON p.id=n.project_id
                WHERE n.id=:id
                """, Map.of("id", id), "工作项不存在");
        projects.decorateNode(node);
        List<Map<String,Object>> taskRows=tasks(id);
        List<Map<String,Object>> bugRows=bugs(id);
        node.put("tasks", taskRows);
        node.put("bugs", bugRows);
        node.put("bugCount", bugRows.size());
        node.put("openBugCount", bugRows.stream().filter(bug -> !"DONE".equals(bug.get("status"))).count());
        node.put("legacyTaskCount", taskRows.stream().filter(task -> !"BUG".equals(task.get("kind"))).count());
        node.put("deliverables", listDeliverables(id));
        node.put("logs", logs(id));
        return node;
    }

    /** 工作项写入口用 READ_COMMITTED：MySQL 默认 RR 下锁前普通读会建快照，锁后复核权限/基线/最大排序会读到旧值。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> createNode(long projectId, Map<String, Object> body) {
        Long requestedModule = longValue(body, "moduleId");
        boolean projectLevel = requestedModule == null || requestedModule == 0;
        String type = workItemType(body, "FEATURE");
        Long moduleOwner = null;
        if (projectLevel) {
            permissions.requireProjectManager(projectId);
        } else {
            Map<String, Object> module = db.one("SELECT id,project_id,owner_id FROM modules WHERE id=:id", Map.of("id", requestedModule), "模块不存在");
            if (number(module.get("projectId")) != projectId) throw BusinessException.badRequest("模块不属于该项目");
            permissions.requireModuleManager(requestedModule);
            moduleOwner = longOrNull(module.get("ownerId"));
        }
        // 显式装箱：long/Long 混合三元会把 null 的模块负责人拆箱成 NPE
        Long owner = ownerValue(body, projectLevel ? Long.valueOf(projectDefaultOwner(projectId)) : moduleOwner);
        LocalDate start = date(body, "planStart"), end = date(body, "planEnd"); checkOptionalRange(start, end);
        if (owner != null) permissions.requireAssignable(projectId, owner);
        Map<String, Object> state = initialState(body, projectId, owner);
        // 先校验交付要求再写工作项：被拒的创建不落半个工作项，也不依赖事务回滚
        List<String> requirements = requirementNames(body);
        Map<String, Object> values = new HashMap<>(state); values.put("project", projectId);
        values.put("module", projectLevel ? null : requestedModule); values.put("type", type); values.put("name", nameText(body));
        values.put("description", text(body, "description")); values.put("start", start); values.put("end", end); values.put("owner", owner);
        values.put("sort", Optional.ofNullable(intValue(body, "sortOrder")).orElse(0)); values.put("creator", permissions.current().id());
        long id = db.insert("""
                INSERT INTO nodes(project_id,module_id,type,name,description,plan_start,plan_end,owner_id,status,historical,actual_start,actual_end,sort_order,created_by)
                VALUES(:project,:module,:type,:name,:description,:start,:end,:owner,:status,:historical,:actualStart,:actualEnd,:sort,:creator)
                """, values);
        // 交付要求是发布工作时一次性约定的承诺：与工作项同一事务落库，负责人与创建人取工作项
        for (int index = 0; index < requirements.size(); index++) {
            db.insert("""
                    INSERT INTO node_deliverables(node_id,project_id,name,status,owner_id,remark,sort_order,created_by)
                    VALUES(:node,:project,:name,'WANTED',:owner,NULL,:sort,:creator)
                    """, map("node", id, "project", projectId, "name", requirements.get(index), "owner", owner, "sort", index, "creator", values.get("creator")));
        }
        Map<String, Object> created = map("name", values.get("name"), "type", type, "requirements", requirements.size(),
                "historical", state.get("historical"), "status", state.get("status"),
                "actualStart", Objects.toString(state.get("actualStart"), null), "actualEnd", Objects.toString(state.get("actualEnd"), null));
        // 补录项创建即越过开工点，不会再经 changeNodeStatus 写基线，因此由创建日志承载完整约定基线
        if (flag(state.get("historical")))
            created.put("requirementBaseline", listDeliverables(id).stream().map(WorkService::requirementSnapshot).toList());
        log(id, "CREATE", projectLevel ? "创建项目级工作项" : "创建工作项", null, created);
        if (owner != null) notifications.notify(owner, "TASK_ASSIGN", "工作项已指派", "/nodes/"+id);
        return node(id);
    }

    /** ADMIN 不一定是项目成员，默认负责人取当前 PM，避免 requireAssignable 失败。 */
    private long projectDefaultOwner(long projectId) {
        Map<String, Object> pm = db.optional("SELECT user_id FROM project_members WHERE project_id=:project AND project_role='PM' LIMIT 1", Map.of("project", projectId));
        return pm == null ? permissions.current().id() : number(pm.get("userId"));
    }

    /** 负责人三态：未传取 fallback，显式 null 表示未指派；0/空串沿用旧客户端口径视为未传。 */
    private static Long ownerValue(Map<String, Object> body, Long fallback) {
        if (!body.containsKey("ownerId")) return fallback;
        if (body.get("ownerId") == null) return null;
        Long value = positiveLong(body, "ownerId");
        return value == null ? fallback : value;
    }

    /** 普通创建只能从未开始起步；historical=true 供 PM/ADMIN 补录已在进行或已完成的工作项。 */
    private Map<String, Object> initialState(Map<String, Object> body, long projectId, Long owner) {
        boolean historical = Boolean.TRUE.equals(bool(body, "historical"));
        String status = text(body, "status");
        LocalDate actualStart = date(body, "actualStart"), actualEnd = date(body, "actualEnd");
        if (!historical) {
            if ((status != null && !"NOT_STARTED".equals(status)) || actualStart != null || actualEnd != null)
                throw BusinessException.badRequest("普通创建只能是未开始状态，补录历史工作项请使用 historical=true");
            return map("status", "NOT_STARTED", "historical", false, "actualStart", null, "actualEnd", null);
        }
        permissions.requireProjectManager(projectId);
        if ("IN_PROGRESS".equals(status)) {
            if (actualStart == null) throw BusinessException.badRequest("actualStart 不能为空");
            if (actualEnd != null) throw BusinessException.badRequest("进行中的历史工作项不能填写 actualEnd");
            if (owner == null) throw BusinessException.badRequest("进行中的工作项必须指派负责人");
        } else if ("DONE".equals(status)) {
            if (actualEnd == null) throw BusinessException.badRequest("actualEnd 不能为空");
        } else throw BusinessException.badRequest("历史工作项状态只能是 IN_PROGRESS 或 DONE");
        LocalDate today = LocalDate.now(clock);
        if ((actualStart != null && actualStart.isAfter(today)) || (actualEnd != null && actualEnd.isAfter(today)))
            throw BusinessException.badRequest("实际日期不能晚于今天");
        if (actualStart != null && actualEnd != null && actualStart.isAfter(actualEnd))
            throw BusinessException.badRequest("实际开始日期不能晚于实际完成日期");
        return map("status", status, "historical", true, "actualStart", actualStart, "actualEnd", actualEnd);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> updateNode(long id, Map<String, Object> body) {
        permissions.requireNodeManager(id);
        Map<String, Object> state = db.one("SELECT status,module_id,actual_start,actual_end,historical FROM nodes WHERE id=:id FOR UPDATE", Map.of("id", id), "工作项不存在");
        // 锁内复核：等锁期间负责人或模块可能已变，授权以锁后状态为准
        permissions.requireNodeManager(id);
        requireLockedFieldsUnchanged(body, state);
        // 只取业务字段：progress 列已弃用，不应再进入变更日志的 before_value
        Map<String, Object> before = db.one("SELECT name,description,type,plan_start,plan_end,owner_id,sort_order,project_id FROM nodes WHERE id=:id", Map.of("id", id), "工作项不存在");
        Long beforeOwner = longOrNull(before.get("ownerId"));
        Long owner = ownerValue(body, beforeOwner);
        if (owner == null && "IN_PROGRESS".equals(Objects.toString(state.get("status"))))
            throw BusinessException.badRequest("进行中的工作项不能清空负责人");
        if (owner != null) permissions.requireAssignable(number(before.get("projectId")), owner);
        Map<String, Object> values = nodeUpdateValues(id, body, before, owner);
        db.update("UPDATE nodes SET name=:name,description=:description,type=:type,plan_start=:start,plan_end=:end,owner_id=:owner,sort_order=:sort WHERE id=:id", values);
        log(id, "RESCHEDULE", "更新工作项", before, values);
        if (owner != null && !owner.equals(beforeOwner)) notifications.notify(owner, "TASK_ASSIGN", "工作项已改派", "/nodes/" + id);
        return node(id);
    }

    /** 部分更新：未传字段沿用原值，显式 null 清空计划日期；名称一旦传入就不能为空。 */
    private static Map<String, Object> nodeUpdateValues(long id, Map<String, Object> body, Map<String, Object> before, Long owner) {
        LocalDate start = body.containsKey("planStart") ? date(body, "planStart") : (LocalDate) before.get("planStart");
        LocalDate end = body.containsKey("planEnd") ? date(body, "planEnd") : (LocalDate) before.get("planEnd");
        checkOptionalRange(start, end);
        Map<String, Object> values = new HashMap<>(); values.put("id", id);
        values.put("name", body.containsKey("name") ? nameText(body) : before.get("name"));
        values.put("description", body.containsKey("description") ? text(body, "description") : before.get("description"));
        values.put("type", workItemType(body, Objects.toString(before.get("type"))));
        values.put("start", start); values.put("end", end); values.put("owner", owner);
        values.put("sort", Optional.ofNullable(intValue(body, "sortOrder")).orElse(((Number) before.get("sortOrder")).intValue()));
        return values;
    }

    /** 模块、状态、实际日期与补录标记各有专门入口；通用更新只容忍原样回传。 */
    private static void requireLockedFieldsUnchanged(Map<String, Object> body, Map<String, Object> state) {
        if (body.containsKey("moduleId") && !Objects.equals(positiveLong(body, "moduleId"), longOrNull(state.get("moduleId"))))
            throw BusinessException.badRequest("不能通过更新工作项修改所属模块");
        if (body.containsKey("status") && !Objects.equals(text(body, "status"), Objects.toString(state.get("status"))))
            throw BusinessException.badRequest("不能通过更新工作项修改状态");
        if ((body.containsKey("actualStart") && !Objects.equals(date(body, "actualStart"), state.get("actualStart")))
                || (body.containsKey("actualEnd") && !Objects.equals(date(body, "actualEnd"), state.get("actualEnd"))))
            throw BusinessException.badRequest("不能通过更新工作项修改实际日期");
        if (body.containsKey("historical") && Boolean.TRUE.equals(bool(body, "historical")) != flag(state.get("historical")))
            throw BusinessException.badRequest("不能通过更新工作项修改历史补录标记");
    }

    private static String correctionReason(Map<String, Object> body) {
        String reason = requiredText(body, "reason");
        if (reason.length() > 500) throw BusinessException.badRequest("reason 不能超过 500 字");
        return reason;
    }

    /** 更正类操作的锁后状态快照；historical 归一成布尔，日志前后值同一口径。 */
    private Map<String, Object> lockNodeState(long id) {
        Map<String, Object> state = db.one("SELECT id,project_id,module_id,owner_id,status,actual_start,actual_end,historical FROM nodes WHERE id=:id FOR UPDATE",
                Map.of("id", id), "工作项不存在");
        state.put("historical", flag(state.get("historical")));
        return state;
    }

    private Map<String, Object> lockCorrection(long id, long projectId, String expectedStatus) {
        Map<String, Object> state = lockNodeState(id);
        permissions.requireProjectManager(projectId);
        if (!expectedStatus.equals(Objects.toString(state.get("status")))) throw BusinessException.conflict("工作项状态已变化，请刷新后重试");
        return state;
    }

    private void checkActualDates(String status, LocalDate start, LocalDate end) {
        if ("IN_PROGRESS".equals(status)) {
            if (start == null) throw BusinessException.badRequest("actualStart 不能为空");
            if (end != null) throw BusinessException.badRequest("进行中的工作项不能填写 actualEnd");
        } else if ("DONE".equals(status)) {
            if (end == null) throw BusinessException.badRequest("actualEnd 不能为空");
        } else throw BusinessException.badRequest("只能更正进行中或已完成工作项的实际日期");
        LocalDate today = LocalDate.now(clock);
        if ((start != null && start.isAfter(today)) || (end != null && end.isAfter(today)))
            throw BusinessException.badRequest("实际日期不能晚于今天");
        if (start != null && end != null && start.isAfter(end))
            throw BusinessException.badRequest("实际开始日期不能晚于实际完成日期");
    }

    /** 模块引用必须显式传入：null 表示项目级，否则为正整数。 */
    private static Long moduleRef(Map<String, Object> body, String key) {
        if (!body.containsKey(key)) throw BusinessException.badRequest(key + " 必须显式传入");
        Long value = longValue(body, key);
        if (value != null && value <= 0) throw BusinessException.badRequest(key + " 必须为 null 或正整数");
        return value;
    }

    private void requireMoveTarget(long projectId, Long moduleId) {
        if (moduleId == null) { permissions.requireProjectManager(projectId); return; }
        Map<String, Object> module = db.one("SELECT id,project_id FROM modules WHERE id=:id", Map.of("id", moduleId), "模块不存在");
        if (number(module.get("projectId")) != projectId) throw BusinessException.badRequest("目标模块不属于该项目");
        permissions.requireModuleManager(moduleId);
    }

    private void updateOne(String sql, Map<String, Object> params) {
        if (db.update(sql, params) != 1) throw BusinessException.conflict("工作项已变化，请刷新后重试");
    }

    private void logCorrection(long id, String action, String detail, Map<String, Object> before, String reason, Object... changes) {
        Map<String, Object> after = new HashMap<>(before);
        after.putAll(map(changes));
        after.put("reason", reason);
        log(id, action, detail, new HashMap<>(before), after);
    }

    /** 布尔列在不同驱动下可能读回 Boolean 或 0/1 数字。 */
    private static boolean flag(Object value) { return value instanceof Boolean b ? b : value instanceof Number n && n.intValue() != 0; }

    /** 开始工作只要求有可指派的负责人，计划日期允许留空。 */
    private void requireStartOwner(Map<String, Object> node) {
        Long owner = longOrNull(node.get("ownerId"));
        if (owner == null) throw BusinessException.badRequest("工作项未指派负责人，不能开始");
        permissions.requireAssignable(number(node.get("projectId")), owner);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String,Object> changeNodeStatus(long id,String target) { return changeNodeStatus(id,target,null); }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> changeNodeStatus(long id, String target, String expectedStatus) {
        permissions.requireNodeManager(id);
        Map<String, Object> before = db.one("SELECT * FROM nodes WHERE id=:id FOR UPDATE", Map.of("id", id), "工作项不存在");
        permissions.requireNodeManager(id);
        String current = Objects.toString(before.get("status"));
        if(expectedStatus!=null && !expectedStatus.isBlank() && !expectedStatus.equals(current))
            throw BusinessException.conflict("工作项状态已变化，请刷新后重试");
        Set<String> allowed = switch (current) {
            case "NOT_STARTED" -> Set.of("IN_PROGRESS", "CANCELLED");
            case "IN_PROGRESS" -> Set.of("DONE", "CANCELLED");
            default -> Set.of();
        };
        if (!allowed.contains(target)) throw BusinessException.conflict("非法工作项状态流转");
        if ("IN_PROGRESS".equals(target)) requireStartOwner(before);
        LocalDate today = LocalDate.now(clock);
        Map<String, Object> values = new HashMap<>(); values.put("id", id); values.put("status", target);
        values.put("actualStart", "IN_PROGRESS".equals(target) && before.get("actualStart") == null ? today : before.get("actualStart"));
        values.put("actualEnd", "DONE".equals(target) ? today : null);
        db.update("UPDATE nodes SET status=:status,actual_start=:actualStart,actual_end=:actualEnd WHERE id=:id", values);
        String action = "DONE".equals(target) ? "COMPLETE" : "CANCELLED".equals(target) ? "CANCEL" : "STATUS_CHANGE";
        Map<String, Object> after = new HashMap<>(Map.of("status", target));
        // 开工即约定生效：把此刻的交付要求整份留作基线，之后的修订日志都能对照它
        if ("IN_PROGRESS".equals(target))
            after.put("requirements", listDeliverables(id).stream().map(WorkService::requirementSnapshot).toList());
        log(id, action, "工作项状态变更为 " + target, Map.of("status", current), after);
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
        if (!(Set.of("NOT_STARTED", "IN_PROGRESS").contains(status) && end != null && end.isBefore(LocalDate.now(clock)))) throw BusinessException.badRequest("工作项当前未延期");
        if (reason.trim().equals(before.get("overdueReason"))) return node(id);
        db.update("UPDATE nodes SET overdue_reason=:reason WHERE id=:id", Map.of("id", id, "reason", reason.trim()));
        log(id, "OVERDUE_REASON", "提交延期原因", map("reason", before.get("overdueReason")), Map.of("reason", reason.trim()));
        notifyManagers(number(before.get("projectId")), longOrNull(before.get("moduleId")), "OVERDUE_ALERT", "工作项延期说明已更新", "/nodes/" + id);
        return node(id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> reopenNode(long id, Map<String, Object> body) {
        long projectId = permissions.projectForNode(id); permissions.requireProjectManager(projectId);
        if (!"DONE".equals(requiredText(body, "expectedStatus"))) throw BusinessException.badRequest("expectedStatus 必须是 DONE");
        String reason = correctionReason(body); Map<String, Object> before = lockCorrection(id, projectId, "DONE");
        Long owner = longOrNull(before.get("ownerId"));
        if (owner == null) throw BusinessException.badRequest("工作项未指派负责人，不能重新打开");
        permissions.requireAssignable(projectId, owner);
        updateOne("UPDATE nodes SET status='IN_PROGRESS',actual_end=NULL WHERE id=:id", Map.of("id", id));
        logCorrection(id, "STATUS_CHANGE", "重新打开工作项", before, reason, "status", "IN_PROGRESS", "actualEnd", null);
        return node(id);
    }

    /** 更正后的实际日期视为补录事实，historical 置 1。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> correctActualDates(long id, Map<String, Object> body) {
        long projectId = permissions.projectForNode(id); permissions.requireProjectManager(projectId);
        String expected = requiredText(body, "expectedStatus"), reason = correctionReason(body);
        LocalDate start = date(body, "actualStart"), end = date(body, "actualEnd"); checkActualDates(expected, start, end);
        Map<String, Object> before = lockCorrection(id, projectId, expected);
        if (Objects.equals(start, before.get("actualStart")) && Objects.equals(end, before.get("actualEnd"))) return node(id);
        updateOne("UPDATE nodes SET actual_start=:start,actual_end=:end,historical=1 WHERE id=:id", map("id", id, "start", start, "end", end));
        logCorrection(id, "RESCHEDULE", "更正实际日期", before, reason, "actualStart", start, "actualEnd", end, "historical", true);
        return node(id);
    }

    /** 只改 module_id：负责人、附件、Bug 与记录都挂在工作项 id 上，随之保留。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> moveNode(long id, Map<String, Object> body) {
        Long target = moduleRef(body, "moduleId"), expected = moduleRef(body, "expectedModuleId");
        String reason = correctionReason(body);
        permissions.requireNodeManager(id); Map<String, Object> before = lockNodeState(id); permissions.requireNodeManager(id);
        if (!Objects.equals(expected, longOrNull(before.get("moduleId")))) throw BusinessException.conflict("工作项所属模块已变化，请刷新后重试");
        requireMoveTarget(number(before.get("projectId")), target);
        if (Objects.equals(target, expected)) return node(id);
        updateOne("UPDATE nodes SET module_id=:module WHERE id=:id", map("id", id, "module", target));
        logCorrection(id, "RESCHEDULE", "移动工作项", before, reason, "moduleId", target);
        return node(id);
    }

    public List<Map<String, Object>> logs(long nodeId) {
        permissions.requireProjectRead(permissions.projectForNode(nodeId));
        List<Map<String, Object>> rows = db.list("SELECT l.*,u.name user_name FROM node_logs l LEFT JOIN users u ON u.id=l.user_id WHERE l.node_id=:node ORDER BY l.created_at DESC,l.id DESC", Map.of("node", nodeId));
        rows.forEach(row -> { row.put("beforeValue", db.parseJson(row.get("beforeValue"))); row.put("afterValue", db.parseJson(row.get("afterValue"))); });
        return rows;
    }

    private static final String TASK_SELECT = "SELECT t.*,u.name assignee_name,related.name related_node_name,"
            + TaskSourceSql.COLUMNS
            + ",(SELECT SUM(w.days) FROM task_work_logs w WHERE w.task_id=t.id) actual_days"
            + ",(SELECT COUNT(*) FROM task_notes notes WHERE notes.task_id=t.id) note_count"
            + TaskSourceSql.FROM + " JOIN users u ON u.id=t.assignee_id ";

    public List<Map<String, Object>> tasks(long nodeId) {
        permissions.requireProjectRead(permissions.projectForNode(nodeId));
        return db.list(TASK_SELECT+" WHERE t.node_id=:node ORDER BY CASE t.status WHEN 'DOING' THEN 0 WHEN 'TODO' THEN 1 ELSE 2 END,t.due_date,t.id", Map.of("node", nodeId));
    }

    public List<Map<String,Object>> bugs(long nodeId) {
        long project=permissions.projectForNode(nodeId); permissions.requireProjectRead(project);
        return db.list(TASK_SELECT+" WHERE t.kind='BUG' AND p.id=:project AND ("+TaskSourceSql.ID+")=:node ORDER BY CASE t.status WHEN 'DOING' THEN 0 WHEN 'TODO' THEN 1 ELSE 2 END,t.id DESC",Map.of("project",project,"node",nodeId));
    }

    /**
     * 工作项交付物清单：回答"这个工作项要交什么、还缺什么、文件在哪"。
     * 文件继续复用 attachments 的工作项级文件，一条清单项可关联其中多个文件，不新建文件体系。
     */
    public List<Map<String, Object>> deliverables(long nodeId) {
        permissions.requireProjectRead(permissions.projectForNode(nodeId));
        return listDeliverables(nodeId);
    }

    private List<Map<String, Object>> listDeliverables(long nodeId) {
        List<Map<String, Object>> rows = db.list(DELIVERABLE_SQL + " WHERE d.node_id=:node ORDER BY d.sort_order, d.id", Map.of("node", nodeId));
        attachAttachments(rows, db.list(DELIVERABLE_ATTACHMENT_SQL
                + " JOIN node_deliverables d ON d.id=l.deliverable_id WHERE d.node_id=:node ORDER BY l.sort_order, l.id",
                Map.of("node", nodeId)));
        return rows;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> createDeliverable(long nodeId, Map<String, Object> body) {
        permissions.requireNodeManager(nodeId);
        // 与新增任务同一锁顺序：先锁工作项行，避免与"开始工作项"并发时绕过开工后的原因校验
        Map<String, Object> node = db.one("SELECT id,project_id,status FROM nodes WHERE id=:id FOR UPDATE", Map.of("id", nodeId), "工作项不存在");
        // 锁内复核：等锁期间负责人或模块可能已变，授权以锁后状态为准
        permissions.requireNodeManager(nodeId);
        requireNodeOpen(node, "工作项已完成或已取消，不能新增交付要求");
        String reason = revisionReason(text(body, "changeReason"), "IN_PROGRESS".equals(Objects.toString(node.get("status"))));
        long projectId = number(node.get("projectId"));
        // 未指定 sortOrder 时排到末尾：列表按 (sort_order, id) 升序，取当前最大值 +1 才等于创建顺序（与创建工作项时的 0..n-1 同一口径）
        Map<String, Object> request = body;
        if (!body.containsKey("sortOrder")) {
            request = new HashMap<>(body);
            request.put("sortOrder", nextDeliverableSort(nodeId));
        }
        Map<String, Object> values = deliverableValues(request, projectId, nodeId, null);
        values.put("node", nodeId); values.put("project", projectId); values.put("creator", permissions.current().id());
        long id = db.insert("""
                INSERT INTO node_deliverables(node_id,project_id,name,status,owner_id,remark,sort_order,created_by)
                VALUES(:node,:project,:name,:status,:owner,:remark,:sort,:creator)
                """, values);
        replaceAttachments(id, asAttachmentIds(values.get("attachments")));
        Map<String, Object> created = deliverable(id);
        logRequirementRevision(nodeId, "DELIVERABLE_ADD", "新增交付物", null, requirementSnapshot(created), reason);
        return created;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> updateDeliverable(long id, Map<String, Object> body) {
        // 先按 id 取所属工作项做授权，再锁工作项与交付物行：避免用锁前快照判断"关闭工作项只允许改状态"
        long nodeId = nodeOfDeliverable(id);
        permissions.requireNodeManager(nodeId);
        Map<String, Object> node = db.one("SELECT id,project_id,status FROM nodes WHERE id=:id FOR UPDATE", Map.of("id", nodeId), "工作项不存在");
        permissions.requireNodeManager(nodeId);
        lockDeliverable(id);
        Map<String, Object> before = deliverable(id);
        List<Long> beforeAttachments = linkedAttachmentIds(before);
        // 关联文件 id 只参与"是否真的改动"的比较，不属于响应字段，读取时单独挂上来
        before.put("attachmentIds", beforeAttachments);
        Map<String, Object> values = deliverableValues(body, number(node.get("projectId")), nodeId, before);
        String nodeStatus = Objects.toString(node.get("status"));
        // 已关闭工作项只允许补标记"已提供"（补资料是常态），改名、改派、换文件一律拒绝，避免事后改写交付事实
        if (Set.of("DONE", "CANCELLED").contains(nodeStatus) && !DeliverableRules.onlyStatusChanged(before, values)) {
            throw BusinessException.conflict("工作项已完成或已取消，只能更新交付物状态");
        }
        // 开工后改名、改派、改排序是修订已生效的约定，必须留下原因；状态、备注与关联文件照常可改
        String reason = revisionReason(text(body, "changeReason"),
                "IN_PROGRESS".equals(nodeStatus) && !DeliverableRules.onlyProgressAllowed(before, values));
        // 整表回传但没有任何变化时不写日志，与任务状态接口的幂等口径一致
        if (DeliverableRules.unchanged(before, values)) {
            before.remove("attachmentIds");
            return before;
        }
        values.put("id", id);
        // 可写列发生变化时，同步更新 DeliverableRules.onlyProgressAllowed / onlyStatusChanged 的比较键，否则开工或关闭工作项上会漏判新增列
        db.update("UPDATE node_deliverables SET name=:name,status=:status,owner_id=:owner,remark=:remark,sort_order=:sort WHERE id=:id", values);
        List<Long> attachments = asAttachmentIds(values.get("attachments"));
        // 关联集合变了才重写关联行；仅顺序不同不算变化（与 DeliverableRules 同口径），避免无意义删插
        if (!new HashSet<>(beforeAttachments).equals(new HashSet<>(attachments))) replaceAttachments(id, attachments);
        Map<String, Object> after = deliverable(id);
        logRequirementRevision(nodeId, "DELIVERABLE_UPDATE", "更新交付物", requirementSnapshot(before), requirementSnapshot(after), reason);
        return after;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteDeliverable(long id) { deleteDeliverable(id, null); }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteDeliverable(long id, String changeReason) {
        long nodeId = nodeOfDeliverable(id);
        permissions.requireNodeManager(nodeId);
        Map<String, Object> node = db.one("SELECT status FROM nodes WHERE id=:id FOR UPDATE", Map.of("id", nodeId), "工作项不存在");
        permissions.requireNodeManager(nodeId);
        lockDeliverable(id);
        Map<String, Object> before = deliverable(id);
        requireNodeOpen(node, "工作项已完成或已取消，不能删除交付要求");
        String reason = revisionReason(changeReason, "IN_PROGRESS".equals(Objects.toString(node.get("status"))));
        // 先删关联行再删主行，同一事务；文件本身保留，交付记录不留孤儿关联
        db.update("DELETE FROM node_deliverable_attachments WHERE deliverable_id=:id", Map.of("id", id));
        if (db.update("DELETE FROM node_deliverables WHERE id=:id", Map.of("id", id)) != 1) throw BusinessException.conflict("交付物已被删除");
        logRequirementRevision(nodeId, "DELIVERABLE_DELETE", "删除交付物", requirementSnapshot(before), null, reason);
    }

    /** 交付要求修订原因：required 时缺失按 409（约定已生效），提供了就校验长度并记入日志。 */
    private static String revisionReason(String raw, boolean required) {
        String reason = raw == null || raw.isBlank() ? null : raw.trim();
        if (reason == null && required) throw BusinessException.conflict("工作项已开始，调整交付要求必须填写变更原因");
        if (reason != null && reason.length() > 500) throw BusinessException.badRequest("变更原因不能超过 500 字");
        return reason;
    }

    /** 交付要求的完整审计快照：日志里能还原修订前后的约定，而不只是名称。 */
    private static Map<String, Object> requirementSnapshot(Map<String, Object> deliverable) {
        return map("deliverableId", deliverable.get("id"), "name", deliverable.get("name"), "status", deliverable.get("status"),
                "ownerId", deliverable.get("ownerId"), "remark", deliverable.get("remark"), "sortOrder", deliverable.get("sortOrder"),
                "attachmentIds", linkedAttachmentIds(deliverable));
    }

    /** 有原因时挂在 afterValue（删除没有 after，则单独成一个只含 id 与原因的 after）。 */
    private void logRequirementRevision(long nodeId, String action, String detail, Map<String, Object> before, Map<String, Object> after, String reason) {
        Map<String, Object> recorded = after;
        if (reason != null) {
            recorded = after == null ? map("deliverableId", before.get("deliverableId")) : new HashMap<>(after);
            recorded.put("reason", reason);
        }
        log(nodeId, action, detail, before, recorded);
    }

    @Transactional
    public Map<String, Object> createTask(long nodeId, Map<String, Object> body) {
        permissions.requireNodeManager(nodeId);
        String kind = taskKind(body, "FEATURE");
        if ("BUG".equals(kind)) {
            Map<String,Object> bugBody = new HashMap<>(body);
            Long related = longValue(body, "relatedNodeId");
            bugBody.put("relatedNodeId", related == null || related == 0 ? nodeId : related);
            return reportBug(permissions.projectForNode(nodeId), bugBody);
        }
        // 锁工作项行，和完成工作项、任务状态变更串行，避免并发下给已完成工作项插入任务
        Map<String, Object> node = db.one("SELECT id,project_id,owner_id,status FROM nodes WHERE id=:id FOR UPDATE", Map.of("id", nodeId), "工作项不存在");
        requireNodeOpen(node, "已完成或已取消的工作项不能新增任务");
        // 工作项可未指派：回退值必须惰性取，否则显式传了执行人也会因空负责人 NPE
        Long assigneeValue = Optional.ofNullable(positiveLong(body, "assigneeId")).orElseGet(() -> longOrNull(node.get("ownerId")));
        if (assigneeValue == null) throw BusinessException.badRequest("工作项未指派负责人，请指定任务执行人");
        long assignee = assigneeValue; permissions.requireAssignable(number(node.get("projectId")), assignee);
        BigDecimal estimate = decimal(body, "estimateDays"); validateDays(estimate, true);
        long projectId = number(node.get("projectId"));
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
        permissions.requireTaskManager(id, false);
        Map<String,Object> before=lockTaskForMutation(id);
        permissions.requireTaskManager(id,false,true);
        requireTaskEditable(before,"已完成或已取消工作项的历史任务不能修改");
        long projectId=permissions.projectForTask(id), nodeId=number(before.get("nodeId"));
        long assignee = Optional.ofNullable(positiveLong(body, "assigneeId")).orElse(number(before.get("assigneeId"))); permissions.requireAssignable(projectId, assignee);
        BigDecimal estimate = decimal(body, "estimateDays"); validateDays(estimate, true);
        String kind = taskKind(body, Objects.toString(before.get("kind")));
        if (!"BUG".equals(before.get("kind")) && "BUG".equals(kind))
            throw BusinessException.badRequest("历史任务不能转换为Bug，请通过报告Bug创建独立问题");
        // Keep an unchanged legacy relation even if its target was removed/malformed; source resolution stays same-project safe.
        Long related = "BUG".equals(before.get("kind"))
                ? (body.containsKey("relatedNodeId") ? longValue(body,"relatedNodeId") : longOrNull(before.get("relatedNodeId")))
                : relatedNodeValue(body, projectId, before.get("relatedNodeId"));
        if("BUG".equals(before.get("kind")) && (!"BUG".equals(kind) || !Objects.equals(longOrNull(before.get("relatedNodeId")),related)))
            throw BusinessException.badRequest("Bug类型和关联功能不可修改");
        Map<String, Object> values = new HashMap<>(); values.put("id", id); values.put("name", nameText(body)); values.put("description", text(body, "description"));
        values.put("kind", kind); values.put("related", related);
        values.put("situation", detailText(body, "situation")); values.put("handling", detailText(body, "handling"));
        values.put("assignee", assignee); values.put("due", date(body, "dueDate")); values.put("estimate", estimate);
        db.update("UPDATE tasks SET name=:name,description=:description,kind=:kind,related_node_id=:related,situation=:situation,handling=:handling,assignee_id=:assignee,due_date=:due,estimate_days=:estimate WHERE id=:id", values);
        log(sourceId(before), "TASK_UPDATE", "BUG".equals(kind)?"更新Bug":"更新历史任务", before, values);
        if (assignee != number(before.get("assigneeId"))) notifyUser(assignee, "TASK_ASSIGN", "BUG".equals(kind)?"Bug已改派":"历史任务已改派", "/nodes/" + nodeId + "?task=" + id);
        return task(id);
    }

    @Transactional
    public void deleteTask(long id) {
        permissions.requireTaskManager(id, false);
        Map<String,Object> task=lockTaskForMutation(id);
        permissions.requireTaskManager(id,false,true);
        requireTaskEditable(task,"已完成或已取消工作项的历史任务不能删除");
        long nodeId=sourceId(task);
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
        if (target == null) throw BusinessException.badRequest("请选择关联的功能工作项");
        long nodeId = number(target.get("id"));
        long assignee = bugAssignee(target, body);
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
        if (target == null) return Map.of("canSubmit", false, "message", "请选择关联的功能工作项");
        Long ownerId = longOrNull(target.get("ownerId"));
        if (ownerId == null) return Map.of("canSubmit", false, "nodeId", target.get("id"), "nodeName", target.get("name"),
                "message", UNASSIGNED_BUG_TARGET);
        long owner = ownerId;
        try { permissions.requireAssignable(projectId, owner); }
        catch (BusinessException ex) {
            if (ex.code() != 4000) throw ex;
            return Map.of("canSubmit", false, "message", "接收负责人已失效，请联系项目经理调整工作项负责人");
        }
        String ownerName = Objects.toString(db.one("SELECT name FROM users WHERE id=:id", Map.of("id", owner), "负责人不存在").get("name"), "");
        return Map.of("canSubmit", true, "nodeId", target.get("id"), "nodeName", target.get("name"),
                "ownerId", owner, "ownerName", ownerName, "message", "将提交给" + ownerName + " · " + target.get("name"));
    }

    /** 有负责人时始终路由给负责人；负责人未指派时才采用显式指定的正整数 assigneeId。 */
    private static long bugAssignee(Map<String, Object> target, Map<String, Object> body) {
        Long owner = longOrNull(target.get("ownerId"));
        if (owner != null) return owner;
        Long requested = longValue(body, "assigneeId");
        if (requested == null || requested <= 0) throw BusinessException.badRequest(UNASSIGNED_BUG_TARGET);
        return requested;
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
            if ("CANCELLED".equals(related.get("status"))) throw BusinessException.conflict("已取消的工作项不能报告Bug");
            return related;
        }
        return null;
    }

    @Transactional
    public Map<String, Object> taskStatus(long id, String status, String expectedStatus) {
        permissions.requireTaskManager(id, true); if (!Set.of("TODO", "DOING", "DONE").contains(status)) throw BusinessException.badRequest("任务状态无效");
        Map<String,Object> before=lockTaskForMutation(id);
        permissions.requireTaskManager(id,true,true);
        String currentStatus=Objects.toString(before.get("status"));
        if (expectedStatus != null && !expectedStatus.isBlank() && !expectedStatus.equals(currentStatus))
            throw BusinessException.conflict("任务状态已变化，请刷新后重试");
        if(status.equals(currentStatus)) return before;
        requireTaskEditable(before,"工作项已完成或已取消，历史任务状态不能再变更");
        LocalDateTime doneAt = "DONE".equals(status) ? LocalDateTime.now(clock) : null;
        db.update("UPDATE tasks SET status=:status,done_at=:done WHERE id=:id", map("status", status, "done", doneAt, "id", id));
        log(sourceId(before), "TASK_STATUS", "BUG".equals(before.get("kind"))?"Bug状态变更":"历史任务状态变更", Map.of("status", currentStatus), Map.of("status", status)); return task(id);
    }

    public List<Map<String, Object>> workLogs(long taskId) {
        permissions.requireProjectRead(permissions.projectForTask(taskId));
        return db.list("SELECT w.*,u.name user_name FROM task_work_logs w JOIN users u ON u.id=w.user_id WHERE w.task_id=:task ORDER BY w.work_date DESC,w.id DESC", Map.of("task", taskId));
    }

    /** 进展记录：每份是命名的 Markdown 文件，按时间倒序，正文里的 attachment:{id} 指向本任务附件。 */
    public List<Map<String, Object>> taskNotes(long taskId) {
        permissions.requireProjectRead(permissions.projectForTask(taskId));
        return db.list(NOTE_SELECT + " WHERE n.task_id = :task ORDER BY n.created_at DESC, n.id DESC", Map.of("task", taskId));
    }

    @Transactional
    public Map<String, Object> addTaskNote(long taskId, Map<String, Object> body) {
        permissions.requireTaskManager(taskId, true);
        String title = noteTitle(body);
        String content = noteContent(body);
        // 与删任务串行：先锁工作项和任务行，避免记录插入时任务已被删除导致外键 500
        long nodeId = number(db.one("SELECT node_id FROM tasks WHERE id=:id", Map.of("id", taskId), "任务不存在").get("nodeId"));
        lockNode(nodeId);
        db.one("SELECT id FROM tasks WHERE id=:id FOR UPDATE", Map.of("id", taskId), "任务不存在");
        // 记录本身带作者与时间，不重复写 node_logs（其 action 枚举未包含记录事件）；时间用注入的 Clock，避免依赖数据库时区
        long id = db.insert("INSERT INTO task_notes(task_id,title,author_id,content,created_at) VALUES(:task,:title,:author,:content,:created)",
                map("task", taskId, "title", title, "author", permissions.current().id(), "content", content,
                        "created", LocalDateTime.now(clock)));
        return taskNote(id);
    }

    /** 再编辑已保存的记录：作者本人或任务管理者；标题、正文校验与新建一致，并记录最后编辑人与时间。 */
    @Transactional
    public Map<String, Object> updateTaskNote(long id, Map<String, Object> body) {
        Map<String, Object> note = db.one("SELECT id,task_id,author_id,title FROM task_notes WHERE id=:id", Map.of("id", id), "记录不存在");
        long taskId = number(note.get("taskId"));
        // 归档项目与 VIEWER 一律拒绝；作者身份只决定能否越过管理者校验，不决定是否检查项目状态
        permissions.requireProjectActive(permissions.projectForTask(taskId));
        if (number(note.get("authorId")) != permissions.current().id()) permissions.requireTaskManager(taskId, false);
        // title 可选：未传或为空时保留原记录名，避免只改正文却把文件静默改名
        String title = text(body, "title") == null ? Objects.toString(note.get("title"), "进展记录.md") : noteTitle(body);
        String content = noteContent(body);
        // 与删任务串行：锁工作项后再改记录，避免任务被删后更新孤儿行
        long nodeId = number(db.one("SELECT node_id FROM tasks WHERE id=:id", Map.of("id", taskId), "任务不存在").get("nodeId"));
        lockNode(nodeId);
        if (db.update("""
                UPDATE task_notes SET title=:title,content=:content,updated_by=:editor,updated_at=:updated
                WHERE id=:id
                """, map("id", id, "title", title, "content", content, "editor", permissions.current().id(),
                        "updated", LocalDateTime.now(clock))) != 1)
            throw BusinessException.notFound("记录不存在");
        return taskNote(id);
    }

    private Map<String, Object> taskNote(long id) {
        return db.one(NOTE_SELECT + " WHERE n.id=:id", Map.of("id", id), "记录不存在");
    }

    /** 记录名即 .md 文件名：缺省为「进展记录.md」，统一补 .md 后缀并拒绝路径与文件系统保留字符。 */
    static String noteTitle(Map<String, Object> body) {
        String title = text(body, "title");
        if (title == null) title = "进展记录.md";
        if (!title.toLowerCase(Locale.ROOT).endsWith(".md")) title += ".md";
        if (title.length() > 120) throw BusinessException.badRequest("记录名称不能超过 120 字");
        if (title.chars().anyMatch(ch -> ch < 32 || "\\/:*?\"<>|".indexOf(ch) >= 0))
            throw BusinessException.badRequest("记录名称不能包含 \\ / : * ? \" < > | 等文件名字符");
        return title;
    }

    static String noteContent(Map<String, Object> body) {
        String content = text(body, "content");
        if (content == null) throw BusinessException.badRequest("记录内容不能为空");
        if (content.length() > 20000) throw BusinessException.badRequest("记录不能超过 20000 字");
        return content;
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

    private Map<String,Object> task(long id) { return db.one(TASK_SELECT+" WHERE t.id=:id",Map.of("id",id),"任务不存在"); }

    private static long sourceId(Map<String,Object> task) { return number(task.get("originNodeId")); }

    /** All legacy storage/source nodes precede the task lock, ordered by id to avoid routed-Bug lock inversions. */
    private Map<String,Object> lockTaskForMutation(long id) {
        Map<String,Object> before=task(id);
        long storage=number(before.get("nodeId")), origin=sourceId(before);
        List<Map<String,Object>> locked=db.list("SELECT id,status,owner_id FROM nodes WHERE id IN(:storage,:origin) ORDER BY id FOR UPDATE",Map.of("storage",storage,"origin",origin));
        Map<String,Object> current=db.one("SELECT * FROM tasks WHERE id=:id FOR UPDATE",Map.of("id",id),"任务不存在");
        Map<String,Object> result=new LinkedHashMap<>(task(id)); result.putAll(current);
        locked.stream().filter(node->number(node.get("id"))==origin).findFirst().ifPresent(node->{
            result.put("originNodeStatus",node.get("status")); result.put("originOwnerId",node.get("ownerId"));
        });
        return result;
    }

    private static void requireTaskEditable(Map<String,Object> task,String legacyMessage) {
        String originStatus=Objects.toString(task.get("originNodeStatus"));
        if("BUG".equals(task.get("kind"))) {
            if("CANCELLED".equals(originStatus)) throw BusinessException.conflict("关联工作项已取消，Bug内容和状态只读");
        } else if(Set.of("DONE","CANCELLED").contains(originStatus)) throw BusinessException.conflict(legacyMessage);
    }

    private Map<String, Object> deliverable(long id) {
        Map<String, Object> row = db.one(DELIVERABLE_SQL + " WHERE d.id=:id", Map.of("id", id), "交付物不存在");
        attachAttachments(List.of(row), db.list(DELIVERABLE_ATTACHMENT_SQL
                + " WHERE l.deliverable_id=:id ORDER BY l.sort_order, l.id", Map.of("id", id)));
        return row;
    }

    /** 先按 id 取所属工作项，用于在锁行前完成授权；读不到按 4004 处理。 */
    private long nodeOfDeliverable(long id) {
        return number(db.one("SELECT node_id FROM node_deliverables WHERE id=:id", Map.of("id", id), "交付物不存在").get("nodeId"));
    }

    /** 与工作项锁保持同一顺序：先锁工作项，再锁交付物行，最后读全量字段。 */
    private void lockDeliverable(long id) {
        db.one("SELECT id FROM node_deliverables WHERE id=:id FOR UPDATE", Map.of("id", id), "交付物不存在");
    }

    /** 把关联文件按 (sort_order, id) 挂到各自交付物的 attachments 上；没有关联的也返回空数组。 */
    private void attachAttachments(List<Map<String, Object>> deliverables, List<Map<String, Object>> links) {
        Map<Long, List<Map<String, Object>>> grouped = new HashMap<>();
        for (Map<String, Object> link : links) {
            long deliverableId = number(link.remove("deliverableId"));
            // attachments.deleted 在 MySQL 是 TINYINT(1)、H2 是 BOOLEAN，统一归一化成契约要求的 0/1
            link.put("deleted", deletedFlag(link.get("deleted")));
            grouped.computeIfAbsent(deliverableId, key -> new ArrayList<>()).add(link);
        }
        for (Map<String, Object> deliverable : deliverables) {
            deliverable.put("attachments", grouped.getOrDefault(number(deliverable.get("id")), List.of()));
        }
    }

    private static int deletedFlag(Object value) {
        if (value instanceof Boolean flag) return flag ? 1 : 0;
        return value != null && number(value) != 0 ? 1 : 0;
    }

    /** 关联行整体替换：先删后插，与交付物写入同一事务；sort_order 取提交顺序，保持调用方给定的展示次序。 */
    private void replaceAttachments(long deliverableId, List<Long> attachmentIds) {
        db.update("DELETE FROM node_deliverable_attachments WHERE deliverable_id=:id", Map.of("id", deliverableId));
        for (int index = 0; index < attachmentIds.size(); index++) {
            db.insert("INSERT INTO node_deliverable_attachments(deliverable_id,attachment_id,sort_order) VALUES(:deliverable,:attachment,:sort)",
                    map("deliverable", deliverableId, "attachment", attachmentIds.get(index), "sort", index));
        }
    }

    /** 已关联文件 id 在 values（attachments）与规则入参（attachmentIds）里都是 List&lt;Long&gt;，集中一处转换。 */
    @SuppressWarnings("unchecked")
    private static List<Long> asAttachmentIds(Object value) {
        return value == null ? List.of() : (List<Long>) value;
    }

    /** 从交付物行的 attachments 取出已关联文件 id，用于"关联集合是否变化"的比较。 */
    @SuppressWarnings("unchecked")
    private static List<Long> linkedAttachmentIds(Map<String, Object> deliverable) {
        if (!(deliverable.get("attachments") instanceof List<?> rows)) return List.of();
        List<Long> ids = new ArrayList<>();
        for (Object row : rows) ids.add(number(((Map<String, Object>) row).get("id")));
        return ids;
    }

    /** 单个 id 解析：整数、不带小数的数值与纯整数字符串都接受，null、小数与非数字一律 400，避免静默丢文件。 */
    private static Long attachmentIdValue(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
            return ((Number) value).longValue();
        // 小数类型只有"本身没有小数部分"才算整数：直接取 long 会把 1.5 静默截成 id=1
        if (value instanceof Number numeric) return exactIdValue(numeric);
        String text = value == null ? "" : value.toString().trim();
        try { return Long.valueOf(text); }
        catch (NumberFormatException ex) { throw BusinessException.badRequest("attachmentIds 必须是整数数组"); }
    }

    /** 小数类型的 id 必须能无损转成 long（1.0 可以，1.5 不行），否则按非整数拒绝。 */
    private static Long exactIdValue(Number value) {
        try { return new BigDecimal(value.toString().trim()).longValueExact(); }
        catch (ArithmeticException | NumberFormatException ex) { throw BusinessException.badRequest("attachmentIds 必须是整数数组"); }
    }

    /** 新增清单项未指定排序时取当前最大 sort_order + 1；工作项行已 FOR UPDATE，读最大值与插入在同一事务内完成。 */
    private int nextDeliverableSort(long nodeId) {
        Object max = db.scalar("SELECT COALESCE(MAX(sort_order),-1) FROM node_deliverables WHERE node_id=:node", Map.of("node", nodeId));
        return ((Number) max).intValue() + 1;
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
        List<Long> attachments = attachmentIdsValue(body, nodeId, before);
        Integer sort = intValue(body, "sortOrder");
        Map<String, Object> values = new HashMap<>();
        values.put("name", name); values.put("status", status); values.put("owner", owner); values.put("remark", remark);
        values.put("attachments", attachments); values.put("sort", sort != null ? sort : before == null ? 0 : ((Number) before.get("sortOrder")).intValue());
        return values;
    }

    /**
     * 关联文件口径：attachmentIds 键不存在表示保留原有全部关联（新增时视为空），传 null 或 [] 表示清空；
     * 元素去重后最多 {@value #MAX_DELIVERABLE_ATTACHMENTS} 个，且必须是本工作项未删除的附件。
     */
    private List<Long> attachmentIdsValue(Map<String, Object> body, long nodeId, Map<String, Object> before) {
        if (!body.containsKey("attachmentIds")) return new ArrayList<>(before == null ? List.of() : asAttachmentIds(before.get("attachmentIds")));
        Object raw = body.get("attachmentIds");
        if (raw == null) return new ArrayList<>();
        if (!(raw instanceof List<?> items)) throw BusinessException.badRequest("attachmentIds 必须是数组");
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        for (Object item : items) unique.add(attachmentIdValue(item));
        if (unique.size() > MAX_DELIVERABLE_ATTACHMENTS)
            throw BusinessException.badRequest("一条交付记录最多关联 " + MAX_DELIVERABLE_ATTACHMENTS + " 个文件");
        // 只校验本次新提交的 id：关联文件被软删除后，未改动的旧关联不能挡住"标为已提供"
        Set<Long> linked = new HashSet<>(before == null ? List.of() : asAttachmentIds(before.get("attachmentIds")));
        for (Long attachmentId : unique) if (!linked.contains(attachmentId)) requireNodeAttachment(nodeId, attachmentId);
        return new ArrayList<>(unique);
    }

    /**
     * 创建工作项时提交的交付要求名称：元素必须是字符串或数字，逐条 trim、忽略空白项，单条 ≤200 字，最多 {@value #MAX_NODE_REQUIREMENTS} 条。
     * 只做取值与校验，落库由 createNode 在同一事务内完成。
     */
    private static List<String> requirementNames(Map<String, Object> body) {
        Object raw = body.get("requirements");
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> items)) throw BusinessException.badRequest("requirements 必须是数组");
        List<String> names = new ArrayList<>();
        for (Object item : items) {
            // 只接受文本与数字：对象、数组、布尔与 null 走 toString() 会落成 "[{a=1}]" 这类脏名称
            if (!(item instanceof String) && !(item instanceof Number)) throw BusinessException.badRequest("交付要求名称必须是文本");
            String name = item.toString().trim();
            if (name.isEmpty()) continue;
            if (name.length() > 200) throw BusinessException.badRequest("交付要求名称不能超过 200 字");
            names.add(name);
        }
        if (names.size() > MAX_NODE_REQUIREMENTS)
            throw BusinessException.badRequest("一个工作项最多约定 " + MAX_NODE_REQUIREMENTS + " 条交付要求");
        return names;
    }

    private void requireNodeAttachment(long nodeId, long attachmentId) {
        Map<String, Object> row = db.optional("SELECT id FROM attachments WHERE id=:id AND owner_type='NODE' AND owner_id=:node AND deleted=0",
                Map.of("id", attachmentId, "node", nodeId));
        if (row == null) throw BusinessException.badRequest("只能关联本工作项已上传的文件");
    }

    private static void requireNodeOpen(Map<String, Object> node, String message) {
        if (Set.of("DONE", "CANCELLED").contains(Objects.toString(node.get("status")))) throw BusinessException.conflict(message);
    }

    /** 读回数据库整型列：按整数字符串严格解析，避免数值直接取 long 时的静默截断。 */
    private static Long longOrNull(Object value) { return value == null ? null : Long.valueOf(value.toString()); }

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
    private static void checkOptionalRange(LocalDate start, LocalDate end) { if (start != null && end != null) checkRange(start, end); }
    private static void validateDays(BigDecimal value, boolean allowZero) {
        if (value == null) return; int cmp = value.compareTo(BigDecimal.ZERO); if ((allowZero && cmp < 0) || (!allowZero && cmp <= 0) || value.scale() > 1) throw BusinessException.badRequest("人天必须为 0.1 精度的有效数值");
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new HashMap<>(); for (int i = 0; i < values.length; i += 2) result.put(values[i].toString(), values[i + 1]); return result;
    }
}
