package com.projtimeline.project;

import com.projtimeline.common.BusinessException;
import com.projtimeline.common.BusinessCalendar;
import com.projtimeline.common.PageResponse;
import com.projtimeline.common.ProgressRules;
import com.projtimeline.data.DataAccess;
import com.projtimeline.security.CurrentUser;
import com.projtimeline.security.PermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;

import static com.projtimeline.common.RequestValues.*;
import static com.projtimeline.security.PermissionService.number;

@Service
public class ProjectService {
    private final DataAccess db;
    private final PermissionService permissions;
    private final Clock clock;

    public ProjectService(DataAccess db, PermissionService permissions, Clock clock) {
        this.db = db;
        this.permissions = permissions;
        this.clock = clock;
    }

    public PageResponse<Map<String, Object>> projects(int page, int size) {
        page = Math.max(1, page); size = Math.min(100, Math.max(1, size));
        CurrentUser user = permissions.current();
        String filter = user.isAdmin() || user.isViewer() ? "" : " JOIN project_members mine ON mine.project_id=p.id AND mine.user_id=:user ";
        Map<String, Object> params = Map.of("user", user.id(), "offset", (page - 1) * size, "size", size);
        long total = db.count("SELECT COUNT(DISTINCT p.id) FROM projects p " + filter, params);
        List<Map<String, Object>> rows = db.list("SELECT DISTINCT p.* FROM projects p " + filter + " ORDER BY p.status,p.created_at DESC LIMIT :offset,:size", params);
        rows.forEach(this::decorateProject);
        return new PageResponse<>(rows, total, page, size);
    }

    @Transactional
    public Map<String, Object> createProject(Map<String, Object> body) {
        CurrentUser user = permissions.current();
        if (user.isViewer()) throw BusinessException.forbidden("只读账号不能创建项目");
        LocalDate start = requiredDate(body, "planStart"), end = date(body, "planEnd");
        checkDateRange(start, end);
        Map<String, Object> values = new HashMap<>();
        values.put("name", requiredText(body, "name")); values.put("code", requiredText(body, "code"));
        values.put("description", text(body, "description")); values.put("start", start); values.put("end", end); values.put("creator", user.id());
        long id = db.insert("INSERT INTO projects(name,code,description,plan_start,plan_end,created_by) VALUES(:name,:code,:description,:start,:end,:creator)", values);
        db.insert("INSERT INTO project_members(project_id,user_id,project_role,joined_at) VALUES(:project,:user,'PM',:today)",
                Map.of("project", id, "user", user.id(), "today", LocalDate.now(clock)));
        return projectDetail(id);
    }

    @Transactional
    public Map<String, Object> updateProject(long id, Map<String, Object> body) {
        permissions.requireProjectManager(id);
        LocalDate start = requiredDate(body, "planStart"), end = date(body, "planEnd");
        checkDateRange(start, end);
        Map<String, Object> boundaryParams = new HashMap<>();
        boundaryParams.put("project", id); boundaryParams.put("start", start); boundaryParams.put("end", end);
        if (db.count("SELECT COUNT(*) FROM project_stage_targets WHERE project_id=:project AND (target_date<:start OR (:end IS NOT NULL AND target_date>:end))", boundaryParams) > 0) {
            throw BusinessException.conflict("项目日期范围不能排除已有阶段性目标");
        }
        Map<String, Object> values = new HashMap<>();
        values.put("id", id); values.put("name", requiredText(body, "name")); values.put("code", requiredText(body, "code"));
        values.put("description", text(body, "description")); values.put("start", start); values.put("end", end);
        db.update("UPDATE projects SET name=:name,code=:code,description=:description,plan_start=:start,plan_end=:end WHERE id=:id", values);
        return projectDetail(id);
    }

    public Map<String, Object> timeline(long projectId) {
        permissions.requireProjectRead(projectId);
        Map<String, Object> project = projectDetail(projectId);
        List<Map<String, Object>> modules = db.list("SELECT m.id,m.name,m.description,m.owner_id,u.name owner_name,m.sort_order FROM modules m JOIN users u ON u.id=m.owner_id WHERE m.project_id=:project ORDER BY m.sort_order,m.id", Map.of("project", projectId));
        List<Map<String, Object>> nodes = db.list("""
                SELECT n.*,u.name owner_name,
                       (SELECT COUNT(*) FROM tasks t WHERE t.node_id=n.id) total_tasks,
                       (SELECT COUNT(*) FROM tasks t WHERE t.node_id=n.id AND t.status='DONE') done_tasks,
                       (SELECT COUNT(*) FROM node_deliverables d WHERE d.node_id=n.id) deliverable_total,
                       (SELECT COUNT(*) FROM node_deliverables d WHERE d.node_id=n.id AND d.status='PROVIDED') deliverable_provided
                FROM nodes n JOIN users u ON u.id=n.owner_id WHERE n.project_id=:project ORDER BY n.plan_start,n.sort_order,n.id
                """, Map.of("project", projectId));
        nodes.forEach(this::decorateNode);
        for (Map<String, Object> module : modules) {
            long moduleId = number(module.get("id"));
            module.put("nodes", nodes.stream().filter(n -> number(n.get("moduleId")) == moduleId).toList());
        }
        project.put("modules", modules);
        project.put("stageTargets", stageTargets(projectId));
        return project;
    }

    public List<Map<String, Object>> members(long projectId) {
        permissions.requireProjectRead(projectId);
        return db.list("SELECT u.id,u.username,u.name,u.email,u.global_role,u.active,pm.project_role,pm.joined_at FROM project_members pm JOIN users u ON u.id=pm.user_id WHERE pm.project_id=:project ORDER BY pm.project_role DESC,u.name", Map.of("project", projectId));
    }

    public List<Map<String, Object>> selectableUsers(long projectId, String scope) {
        permissions.requireProjectRead(projectId);
        if ("available".equals(scope)) {
            permissions.requireProjectManager(projectId);
            return db.list("SELECT id,username,name,email,global_role,active FROM users WHERE active=1 AND id NOT IN(SELECT user_id FROM project_members WHERE project_id=:project) ORDER BY name", Map.of("project", projectId));
        }
        return db.list("SELECT u.id,u.username,u.name,u.email,u.global_role,u.active FROM project_members pm JOIN users u ON u.id=pm.user_id WHERE pm.project_id=:project AND u.active=1 AND u.global_role<>'VIEWER' ORDER BY u.name", Map.of("project", projectId));
    }

    @Transactional
    public Map<String, Object> addMember(long projectId, Map<String, Object> body) {
        permissions.requireProjectManager(projectId);
        Long requestedUserId = longValue(body, "userId");
        if (requestedUserId == null) throw BusinessException.badRequest("userId 不能为空");
        long userId = requestedUserId;
        Map<String, Object> user = db.one("SELECT id,active,global_role FROM users WHERE id=:id", Map.of("id", userId), "用户不存在");
        if (!truthy(user.get("active"))) throw BusinessException.badRequest("不能添加停用账号");
        db.insert("INSERT INTO project_members(project_id,user_id,project_role,joined_at) VALUES(:project,:user,'MEMBER',:today)",
                Map.of("project", projectId, "user", userId, "today", LocalDate.now(clock)));
        return db.one("SELECT pm.id,pm.project_id,pm.user_id,pm.project_role,pm.joined_at FROM project_members pm WHERE pm.project_id=:project AND pm.user_id=:user", Map.of("project", projectId, "user", userId), "成员不存在");
    }

    @Transactional
    public void removeMember(long projectId, long userId) {
        permissions.requireProjectManager(projectId);
        Map<String, Object> member = db.one("SELECT id,project_role FROM project_members WHERE project_id=:project AND user_id=:user", Map.of("project", projectId, "user", userId), "成员不存在");
        if ("PM".equals(member.get("projectRole"))) throw BusinessException.conflict("必须先转交项目经理");
        long duties = db.count("SELECT (SELECT COUNT(*) FROM modules WHERE project_id=:project AND owner_id=:user) + (SELECT COUNT(*) FROM nodes WHERE project_id=:project AND owner_id=:user) + (SELECT COUNT(*) FROM tasks t JOIN nodes n ON n.id=t.node_id WHERE n.project_id=:project AND t.assignee_id=:user)", Map.of("project", projectId, "user", userId));
        if (duties > 0) throw BusinessException.conflict("该成员仍承担模块、节点或任务责任");
        db.update("DELETE FROM project_members WHERE project_id=:project AND user_id=:user", Map.of("project", projectId, "user", userId));
    }

    @Transactional
    public void transferPm(long projectId, Map<String, Object> body) {
        permissions.requireProjectManager(projectId);
        Long requestedUserId = longValue(body, "userId");
        if (requestedUserId == null) throw BusinessException.badRequest("userId 不能为空");
        long next = requestedUserId;
        permissions.requireAssignable(projectId, next);
        db.one("SELECT id FROM projects WHERE id=:id FOR UPDATE", Map.of("id", projectId), "项目不存在");
        db.update("UPDATE project_members SET project_role='MEMBER' WHERE project_id=:project AND project_role='PM'", Map.of("project", projectId));
        if (db.update("UPDATE project_members SET project_role='PM' WHERE project_id=:project AND user_id=:user", Map.of("project", projectId, "user", next)) != 1) {
            throw BusinessException.conflict("项目经理转交失败");
        }
    }

    public List<Map<String, Object>> modules(long projectId) {
        permissions.requireProjectRead(projectId);
        List<Map<String, Object>> rows = db.list("SELECT m.*,u.name owner_name FROM modules m JOIN users u ON u.id=m.owner_id WHERE m.project_id=:project ORDER BY m.sort_order,m.id", Map.of("project", projectId));
        rows.forEach(this::decorateModule);
        return rows;
    }

    @Transactional
    public Map<String, Object> createModule(long projectId, Map<String, Object> body) {
        permissions.requireProjectManager(projectId);
        Long requestedOwnerId = longValue(body, "ownerId");
        if (requestedOwnerId == null) throw BusinessException.badRequest("ownerId 不能为空");
        long ownerId = requestedOwnerId;
        permissions.requireAssignable(projectId, ownerId);
        Map<String, Object> values = new HashMap<>(); values.put("project", projectId); values.put("name", requiredText(body, "name"));
        values.put("description", text(body, "description"));
        values.put("owner", ownerId); values.put("sort", Optional.ofNullable(intValue(body, "sortOrder")).orElse(0));
        long id = db.insert("INSERT INTO modules(project_id,name,description,owner_id,sort_order) VALUES(:project,:name,:description,:owner,:sort)", values);
        return db.one("SELECT m.*,u.name owner_name FROM modules m JOIN users u ON u.id=m.owner_id WHERE m.id=:id", Map.of("id", id), "模块不存在");
    }

    @Transactional
    public Map<String, Object> updateModule(long id, Map<String, Object> body) {
        Map<String, Object> module = db.one("SELECT id,project_id,sort_order FROM modules WHERE id=:id", Map.of("id", id), "模块不存在");
        long projectId = number(module.get("projectId"));
        permissions.requireProjectManager(projectId);
        Long requestedOwnerId = longValue(body, "ownerId");
        if (requestedOwnerId == null) throw BusinessException.badRequest("ownerId 不能为空");
        long ownerId = requestedOwnerId; permissions.requireAssignable(projectId, ownerId);
        Map<String, Object> values = new HashMap<>(); values.put("id", id); values.put("name", requiredText(body, "name"));
        values.put("description", text(body, "description"));
        // 未显式传 sortOrder 时保留原顺序，避免改名把模块排到最前
        values.put("owner", ownerId); values.put("sort", Optional.ofNullable(intValue(body, "sortOrder")).orElse((int) number(module.get("sortOrder"))));
        db.update("UPDATE modules SET name=:name,description=:description,owner_id=:owner,sort_order=:sort WHERE id=:id", values);
        return db.one("SELECT m.*,u.name owner_name FROM modules m JOIN users u ON u.id=m.owner_id WHERE m.id=:id", Map.of("id", id), "模块不存在");
    }

    @Transactional
    public void deleteModule(long id) {
        // 先锁模块行：与审批/撤回的加锁方向保持一致（模块 → 申请），避免与并发审批互相等待
        Map<String, Object> module = db.one("SELECT id,project_id FROM modules WHERE id=:id FOR UPDATE", Map.of("id", id), "模块不存在");
        permissions.requireProjectManager(number(module.get("projectId")));
        if (db.count("SELECT COUNT(*) FROM nodes WHERE module_id=:id", Map.of("id", id)) > 0) throw BusinessException.conflict("含节点的模块不能删除");
        // 变更申请外键指向模块：待审批的先让 PM 处理；历史记录随模块一起删除（模块没了，记录也无从查看）
        if (db.count("SELECT COUNT(*) FROM module_change_requests WHERE module_id=:id AND status='PENDING'", Map.of("id", id)) > 0) {
            throw BusinessException.conflict("该模块有待审批的变更申请，请先审批或驳回");
        }
        db.update("DELETE FROM module_change_requests WHERE module_id=:id", Map.of("id", id));
        // 模块文档随之软删除，避免不可见却仍可按 id 下载的孤儿附件
        db.update("UPDATE attachments SET deleted=1 WHERE owner_type='MODULE' AND owner_id=:id", Map.of("id", id));
        db.update("DELETE FROM modules WHERE id=:id", Map.of("id", id));
    }

    public List<Map<String, Object>> stageTargets(long projectId) {
        permissions.requireProjectRead(projectId);
        List<Map<String, Object>> rows = db.list("SELECT * FROM project_stage_targets WHERE project_id=:project ORDER BY target_date,sort_order,id", Map.of("project", projectId));
        rows.forEach(this::decorateStageTarget);
        return rows;
    }

    @Transactional
    public Map<String, Object> createStageTarget(long projectId, Map<String, Object> body) {
        permissions.requireProjectManager(projectId);
        LocalDate targetDate = requiredDate(body, "targetDate");
        validateStageTargetDate(projectId, targetDate);
        Map<String, Object> values = stageTargetValues(body, targetDate, null);
        values.put("project", projectId);
        long id = db.insert("""
                INSERT INTO project_stage_targets(project_id,name,description,target_date,sort_order,
                                                  metric_name,metric_unit,metric_target,metric_current)
                VALUES(:project,:name,:description,:date,:sort,:metricName,:metricUnit,:metricTarget,:metricCurrent)
                """, values);
        return stageTarget(id);
    }

    @Transactional
    public Map<String, Object> updateStageTarget(long id, Map<String, Object> body) {
        Map<String, Object> target = db.one("SELECT id,project_id,metric_name,metric_unit,metric_target,metric_current FROM project_stage_targets WHERE id=:id",
                Map.of("id", id), "阶段性目标不存在");
        long projectId = number(target.get("projectId")); permissions.requireProjectManager(projectId);
        LocalDate targetDate = requiredDate(body, "targetDate"); validateStageTargetDate(projectId, targetDate);
        // 旧前端缓存或第三方调用只传名称/日期时保留原指标；显式传 null 才算清空
        Map<String, Object> values = stageTargetValues(body, targetDate, target);
        values.put("id", id);
        db.update("""
                UPDATE project_stage_targets
                SET name=:name,description=:description,target_date=:date,sort_order=:sort,
                    metric_name=:metricName,metric_unit=:metricUnit,metric_target=:metricTarget,metric_current=:metricCurrent
                WHERE id=:id
                """, values);
        return stageTarget(id);
    }

    @Transactional
    public void deleteStageTarget(long id) {
        Map<String, Object> target = db.one("SELECT id,project_id FROM project_stage_targets WHERE id=:id", Map.of("id", id), "阶段性目标不存在");
        permissions.requireProjectManager(number(target.get("projectId")));
        db.update("DELETE FROM project_stage_targets WHERE id=:id", Map.of("id", id));
    }

    @Transactional
    public void archive(long projectId) { permissions.requireProjectManager(projectId); db.update("UPDATE projects SET status='ARCHIVED' WHERE id=:id", Map.of("id", projectId)); }

    @Transactional
    public void unarchive(long projectId) {
        permissions.requireProjectRead(projectId);
        if (!permissions.current().isAdmin() && !permissions.isPm(projectId)) throw BusinessException.forbidden("仅项目经理可恢复项目");
        db.update("UPDATE projects SET status='ACTIVE' WHERE id=:id", Map.of("id", projectId));
    }

    private Map<String, Object> projectDetail(long id) {
        Map<String, Object> row = db.one("SELECT p.*,u.name creator_name FROM projects p LEFT JOIN users u ON u.id=p.created_by WHERE p.id=:id", Map.of("id", id), "项目不存在");
        decorateProject(row); return row;
    }

    private void decorateProject(Map<String, Object> project) {
        List<Map<String, Object>> nodes = db.list("""
                SELECT n.status,n.plan_end,n.blocked,
                       (SELECT COUNT(*) FROM tasks t WHERE t.node_id=n.id) total_tasks,
                       (SELECT COUNT(*) FROM tasks t WHERE t.node_id=n.id AND t.status='DONE') done_tasks
                FROM nodes n WHERE n.project_id=:project
                """, Map.of("project", project.get("id")));
        int total = nodes.size(), done = 0, inProgress = 0, overdue = 0, blocked = 0, near = 0, progressSum = 0, active = 0;
        for (Map<String, Object> node : nodes) {
            String status = Objects.toString(node.get("status"));
            if ("DONE".equals(status)) done++; if ("IN_PROGRESS".equals(status)) inProgress++;
            Map<String, Boolean> flags = flags(status, (LocalDate) node.get("planEnd"), truthy(node.get("blocked")));
            if (flags.get("overdue")) overdue++; if (flags.get("near")) near++; if (flags.get("blocked")) blocked++;
            if (!"CANCELLED".equals(status)) { progressSum += nodeProgress(node); active++; }
        }
        project.put("progress", ProgressRules.aggregate(active, progressSum, done));
        project.put("stats", Map.of("total", total, "done", done, "inProgress", inProgress, "overdue", overdue, "blocked", blocked, "near", near));
    }

    private void decorateModule(Map<String, Object> module) {
        List<Map<String, Object>> nodes = db.list("""
                SELECT n.status,n.plan_end,n.blocked,
                       (SELECT COUNT(*) FROM tasks t WHERE t.node_id=n.id) total_tasks,
                       (SELECT COUNT(*) FROM tasks t WHERE t.node_id=n.id AND t.status='DONE') done_tasks
                FROM nodes n WHERE n.module_id=:id
                """, Map.of("id", module.get("id")));
        int active = 0, sum = 0, overdue = 0, doneNodes = 0;
        for (Map<String, Object> node : nodes) {
            String status = Objects.toString(node.get("status"));
            if (!"CANCELLED".equals(status)) {
                active++; sum += nodeProgress(node);
                if ("DONE".equals(status)) doneNodes++;
            }
            if (flags(status, (LocalDate) node.get("planEnd"), truthy(node.get("blocked"))).get("overdue")) overdue++;
        }
        module.put("progress", ProgressRules.aggregate(active, sum, doneNodes)); module.put("nodeCount", nodes.size()); module.put("overdueCount", overdue);
    }

    public void decorateNode(Map<String, Object> node) {
        node.put("flags", flags(Objects.toString(node.get("status")), (LocalDate) node.get("planEnd"), truthy(node.get("blocked"))));
        node.put("progress", nodeProgress(node));
    }

    /** 节点进度由任务完成率派生；调用方查询需带 total_tasks / done_tasks 两列。 */
    private static int nodeProgress(Map<String, Object> node) {
        return ProgressRules.nodeProgress(Objects.toString(node.get("status")), intOf(node.get("totalTasks")), intOf(node.get("doneTasks")));
    }

    private static int intOf(Object value) { return value == null ? 0 : ((Number) value).intValue(); }

    private Map<String, Boolean> flags(String status, LocalDate end, boolean blocked) {
        return BusinessCalendar.nodeFlags(status, end, blocked, clock);
    }

    private static void checkDateRange(LocalDate start, LocalDate end) {
        if (start != null && end != null && start.isAfter(end)) throw BusinessException.badRequest("计划开始日期不能晚于结束日期");
    }

    private void validateStageTargetDate(long projectId, LocalDate targetDate) {
        Map<String, Object> project = db.one("SELECT plan_start,plan_end FROM projects WHERE id=:id", Map.of("id", projectId), "项目不存在");
        LocalDate projectStart = (LocalDate) project.get("planStart");
        LocalDate projectEnd = (LocalDate) project.get("planEnd");
        if (targetDate.isBefore(projectStart)) throw BusinessException.badRequest("阶段性目标日期不能早于项目开始日期");
        if (projectEnd != null && targetDate.isAfter(projectEnd)) throw BusinessException.badRequest("阶段性目标日期不能晚于项目完成日期");
    }

    private Map<String, Object> stageTarget(long id) {
        Map<String, Object> row = db.one("SELECT * FROM project_stage_targets WHERE id=:id", Map.of("id", id), "阶段性目标不存在");
        decorateStageTarget(row);
        return row;
    }

    /** 量化进度实时派生，不落库；没有指标（日期型里程碑）时返回 null，前端据此决定是否显示进度。 */
    private void decorateStageTarget(Map<String, Object> row) {
        Object target = row.get("metricTarget");
        row.put("progress", target == null ? null : ProgressRules.metricProgress((BigDecimal) row.get("metricCurrent"), (BigDecimal) target));
    }

    /** 名称、说明、目标日期与排序照旧；量化指标四列可选，existing 非空时表示编辑，未传指标字段则保留原值。 */
    private Map<String, Object> stageTargetValues(Map<String, Object> body, LocalDate targetDate, Map<String, Object> existing) {
        Map<String, Object> values = new HashMap<>();
        values.put("name", requiredText(body, "name"));
        values.put("description", text(body, "description"));
        values.put("date", targetDate);
        values.put("sort", Optional.ofNullable(intValue(body, "sortOrder")).orElse(0));
        Map<String, Object> metric = metricValues(body);
        if (metric == null) metric = existing == null ? emptyMetric() : metricOfRow(existing);
        values.putAll(metric);
        return values;
    }

    private static Map<String, Object> emptyMetric() {
        Map<String, Object> values = new HashMap<>();
        values.put("metricName", null); values.put("metricUnit", null);
        values.put("metricTarget", null); values.put("metricCurrent", null);
        return values;
    }

    private static Map<String, Object> metricOfRow(Map<String, Object> row) {
        Map<String, Object> values = new HashMap<>();
        values.put("metricName", row.get("metricName")); values.put("metricUnit", row.get("metricUnit"));
        values.put("metricTarget", row.get("metricTarget")); values.put("metricCurrent", row.get("metricCurrent"));
        return values;
    }

    /**
     * 量化指标：四个字段都没出现在请求体里时返回 null（调用方保留原值，兼容旧前端缓存）；
     * 出现任意一个就按"要么全空=日期型里程碑，要么必须给名称与大于 0 的目标值"处理。
     */
    private Map<String, Object> metricValues(Map<String, Object> body) {
        boolean touched = body.containsKey("metricName") || body.containsKey("metricUnit")
                || body.containsKey("metricTarget") || body.containsKey("metricCurrent");
        if (!touched) return null;
        String name = text(body, "metricName");
        String unit = text(body, "metricUnit");
        BigDecimal target = decimal(body, "metricTarget");
        BigDecimal current = decimal(body, "metricCurrent");
        if (name == null && unit == null && target == null && current == null) return emptyMetric();
        if (name == null) throw BusinessException.badRequest("填写量化指标时必须给出指标名称");
        if (name.length() > 100) throw BusinessException.badRequest("指标名称不能超过 100 字");
        if (target == null) throw BusinessException.badRequest("填写量化指标时必须给出目标值");
        if (target.signum() <= 0) throw BusinessException.badRequest("目标值必须大于 0");
        // 列是 DECIMAL(12,2)：超出范围或超过两位小数会被数据库取整/报错，这里提前按 4000 拒绝
        checkMetricNumber(target, "目标值");
        if (current != null) {
            if (current.signum() < 0) throw BusinessException.badRequest("当前值不能小于 0");
            checkMetricNumber(current, "当前值");
        }
        if (unit != null && unit.length() > 20) throw BusinessException.badRequest("指标单位不能超过 20 字");
        Map<String, Object> values = new HashMap<>();
        values.put("metricName", name); values.put("metricUnit", unit);
        values.put("metricTarget", target); values.put("metricCurrent", current == null ? BigDecimal.ZERO : current);
        return values;
    }

    /** DECIMAL(12,2) 能存的最大值；正整数部分最多 10 位，小数最多 2 位。 */
    private static final BigDecimal METRIC_MAX = new BigDecimal("9999999999.99");

    private static void checkMetricNumber(BigDecimal value, String label) {
        if (value.abs().compareTo(METRIC_MAX) > 0) throw BusinessException.badRequest(label + "超出可存储范围");
        if (value.stripTrailingZeros().scale() > 2) throw BusinessException.badRequest(label + "最多两位小数");
    }

    private static LocalDate requiredDate(Map<String, Object> body, String key) {
        LocalDate value = date(body, key);
        if (value == null) throw BusinessException.badRequest(key + " 不能为空");
        return value;
    }

    private static boolean truthy(Object value) { return value instanceof Boolean b ? b : value != null && ((Number) value).intValue() != 0; }
}
