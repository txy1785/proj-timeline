package com.projtimeline.notification;

import com.projtimeline.data.DataAccess;
import com.projtimeline.security.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 站内通知的唯一写入口。调用方传入 `notifications.type` 枚举值、动作标题和站内相对路径；
 * 接收人是当前操作者时跳过（无登录上下文的系统通知照常发送）；工作项链接在此统一补项目、工作项与操作者名称。
 * 通知在调用方的事务里随业务写入一起提交，不单独开事务。读取与已读仍由 ReportingService 负责。
 */
@Service
public class NotificationService {
    /** notifications.title 列为 VARCHAR(200)。 */
    private static final int MAX_TITLE = 200;
    /** 位数上限防止超长数字解析溢出。 */
    private static final Pattern NODE_LINK = Pattern.compile("^/nodes/(\\d{1,18})(?:\\?task=\\d{1,18})?$");

    private final DataAccess db;

    public NotificationService(DataAccess db) { this.db = db; }

    public void notify(long userId, String type, String title, String link) {
        Long actor = currentActorId();
        if (actor != null && actor == userId) return;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("user", userId);
        params.put("type", type);
        params.put("title", limit(withContext(title.trim(), link, actor)));
        params.put("link", link);
        db.insert("INSERT INTO notifications(user_id,type,title,link) VALUES(:user,:type,:title,:link)", params);
    }

    /** 通知项目经理：项目可能没有 PM（历史数据），此时静默跳过。 */
    public void notifyProjectPm(long projectId, String type, String title, String link) {
        db.list("SELECT user_id FROM project_members WHERE project_id=:project AND project_role='PM'", Map.of("project", projectId))
                .forEach(row -> notify(((Number) row.get("userId")).longValue(), type, title, link));
    }

    private static Long currentActorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof CurrentUser user ? user.id() : null;
    }

    /** 工作项不存在或链接不是工作项时保留原动作标题，不让通知因补充信息失败。 */
    private String withContext(String action, String link, Long actor) {
        Matcher matcher = link == null ? null : NODE_LINK.matcher(link);
        if (matcher == null || !matcher.matches()) return action;
        Map<String, Object> params = new HashMap<>();
        params.put("node", Long.valueOf(matcher.group(1)));
        params.put("actor", actor);
        Map<String, Object> row = db.optional("""
                SELECT p.name project_name,n.name node_name,(SELECT u.name FROM users u WHERE u.id=:actor) actor_name
                FROM nodes n JOIN projects p ON p.id=n.project_id WHERE n.id=:node
                """, params);
        if (row == null) return action;
        String context = action + "：" + row.get("projectName") + " / " + row.get("nodeName");
        return row.get("actorName") == null ? context : context + "（操作人：" + row.get("actorName") + "）";
    }

    private static String limit(String title) {
        if (title.codePointCount(0, title.length()) <= MAX_TITLE) return title;
        return title.substring(0, title.offsetByCodePoints(0, MAX_TITLE)).trim();
    }
}
