package com.projtimeline.notification;

import com.projtimeline.data.DataAccess;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 站内通知的唯一写入口。调用方负责传入 `notifications.type` 枚举里的类型值、≤200 字的标题和站内相对路径；
 * 通知在调用方的事务里随业务写入一起提交，不单独开事务。读取与已读仍由 ReportingService 负责。
 */
@Service
public class NotificationService {
    private final DataAccess db;

    public NotificationService(DataAccess db) { this.db = db; }

    public void notify(long userId, String type, String title, String link) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("user", userId);
        params.put("type", type);
        params.put("title", title);
        params.put("link", link);
        db.insert("INSERT INTO notifications(user_id,type,title,link) VALUES(:user,:type,:title,:link)", params);
    }

    /** 通知项目经理：项目可能没有 PM（历史数据），此时静默跳过。 */
    public void notifyProjectPm(long projectId, String type, String title, String link) {
        db.list("SELECT user_id FROM project_members WHERE project_id=:project AND project_role='PM'", Map.of("project", projectId))
                .forEach(row -> notify(((Number) row.get("userId")).longValue(), type, title, link));
    }
}
