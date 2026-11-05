package com.projtimeline.notification;

import com.projtimeline.support.UxDatabaseFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationServiceTest {
    private UxDatabaseFixture f;
    private NotificationService notifications;

    @BeforeEach void setup() { f = new UxDatabaseFixture(); notifications = new NotificationService(f.db); f.login(1, "USER"); }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void actorNeverNotifiesThemselves() {
        notifications.notify(1, "TASK_ASSIGN", "工作项已指派", "/nodes/101");
        assertThat(newRows()).isEmpty();
    }

    @Test void otherRecipientGetsProjectWorkItemActorAndAction() {
        notifications.notify(2, "TASK_ASSIGN", "  工作项已指派  ", "/nodes/101");
        List<Map<String, Object>> rows = newRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("user_id", 2L).containsEntry("type", "TASK_ASSIGN").containsEntry("link", "/nodes/101");
        assertThat(rows.get(0).get("title").toString())
                .startsWith("工作项已指派").contains("共享商城").contains("订单接口开发").contains("PM");
    }

    @Test void taskLinkIsEnrichedFromItsWorkItem() {
        notifications.notify(3, "TASK_ASSIGN", "新任务已指派", "/nodes/102?task=1010");
        assertThat(onlyTitle()).startsWith("新任务已指派").contains("共享商城").contains("缺陷修复").contains("PM");
    }

    @Test void systemNotificationWithoutAuthenticationIsStillSent() {
        SecurityContextHolder.clearContext();
        notifications.notify(1, "OVERDUE_ALERT", "工作项已延期", "/nodes/101");
        assertThat(onlyTitle()).contains("共享商城").contains("订单接口开发").doesNotContain("操作人");
    }

    @Test void nonNodeOrMissingNodeLinksKeepActionTitle() {
        notifications.notify(2, "WEEKLY_PUBLISHED", "项目周报已发布", "/projects/10/reports?week=2026-10-26");
        notifications.notify(2, "TASK_ASSIGN", "工作项已指派", "/nodes/999999");
        notifications.notify(2, "TASK_ASSIGN", "工作项已指派", "/nodes/101/../../admin");
        assertThat(newRows()).extracting(row -> row.get("title"))
                .containsExactly("项目周报已发布", "工作项已指派", "工作项已指派");
    }

    @Test void enrichedTitleStaysWithinColumnLength() {
        f.jdbc.update("UPDATE nodes SET name=? WHERE id=101", "长".repeat(200));
        notifications.notify(2, "TASK_ASSIGN", "工作项已指派", "/nodes/101");
        assertThat(onlyTitle()).startsWith("工作项已指派：共享商城").hasSizeLessThanOrEqualTo(200);
    }

    @Test void projectPmNotificationSkipsActingPmButReachesPmForOthers() {
        notifications.notifyProjectPm(10, "NODE_DONE", "工作项已完成", "/nodes/101");
        assertThat(newRows()).isEmpty();
        f.login(3, "USER");
        notifications.notifyProjectPm(10, "NODE_DONE", "工作项已完成", "/nodes/101");
        assertThat(newRows()).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("user_id", 1L);
            assertThat(row.get("title").toString()).contains("模块负责人").contains("订单接口开发");
        });
    }

    private List<Map<String, Object>> newRows() {
        return f.jdbc.queryForList("SELECT user_id,type,title,link FROM notifications WHERE id>=100 ORDER BY id");
    }

    private String onlyTitle() {
        List<Map<String, Object>> rows = newRows();
        assertThat(rows).hasSize(1);
        return rows.get(0).get("title").toString();
    }
}
