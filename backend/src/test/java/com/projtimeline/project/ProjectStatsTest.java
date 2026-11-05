package com.projtimeline.project;

import com.projtimeline.support.UxDatabaseFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Fixture today = 2026-10-27; project 10 baseline: 101 overdue, 102 blocked, 103 DONE, 104 CANCELLED. */
class ProjectStatsTest {
    private UxDatabaseFixture f;

    @BeforeEach void setup() { f = new UxDatabaseFixture(); f.login(1, "USER"); }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void statsCountUnscheduledUnassignedAndEmptyModulesWithoutChangingExistingCounts() {
        f.jdbc.execute("INSERT INTO modules(id,project_id,name,owner_id) VALUES(500,10,'只有取消项',3),(600,10,'尚未拆分',3)");
        rawNode(105, null, "NOT_STARTED", null);
        rawNode(106, null, "DONE", 1L);
        rawNode(107, 500L, "CANCELLED", null);

        Map<String, Object> stats = stats(10);
        assertThat(stats).containsEntry("total", 5).containsEntry("done", 2).containsEntry("inProgress", 2)
                .containsEntry("overdue", 1).containsEntry("blocked", 1).containsEntry("near", 0)
                .containsEntry("unscheduled", 1).containsEntry("unassigned", 1).containsEntry("emptyModules", 2L);
    }

    @Test void undatedOpenItemsAreUnscheduledNeverOverdueOrNear() {
        f.jdbc.execute("UPDATE nodes SET plan_start=NULL,plan_end=NULL WHERE project_id=10");
        Map<String, Object> stats = stats(10);
        assertThat(stats).containsEntry("overdue", 0).containsEntry("near", 0)
                .containsEntry("unscheduled", 2).containsEntry("unassigned", 0)
                .containsEntry("total", 3).containsEntry("emptyModules", 0L);
    }

    @Test void moduleStatsForEmptyModuleStayZero() {
        f.jdbc.execute("INSERT INTO modules(id,project_id,name,owner_id) VALUES(600,10,'尚未拆分',3)");
        Map<String, Object> module = f.projects.modules(10).stream()
                .filter(row -> ((Number) row.get("id")).longValue() == 600).findFirst().orElseThrow();
        assertThat(module).containsEntry("nodeCount", 0).containsEntry("overdueCount", 0)
                .containsEntry("totalWorkItems", 0).containsEntry("doneWorkItems", 0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void timelineNodesCarryModuleOwnerAndModulesCarryProjectWithOneRowPerNode() {
        f.jdbc.execute("ALTER TABLE projects ADD COLUMN created_by BIGINT");
        f.jdbc.execute("CREATE TABLE project_stage_targets(id BIGINT PRIMARY KEY,project_id BIGINT,target_date DATE,sort_order INT,metric_target DECIMAL(12,2),metric_current DECIMAL(12,2))");
        Map<String, Object> timeline = f.projects.timeline(10);
        List<Map<String, Object>> modules = (List<Map<String, Object>>) timeline.get("modules");
        Map<String, Object> order = modules.stream().filter(row -> ((Number) row.get("id")).longValue() == 100).findFirst().orElseThrow();
        List<Map<String, Object>> moduleNodes = (List<Map<String, Object>>) order.get("nodes");
        List<Map<String, Object>> projectItems = (List<Map<String, Object>>) timeline.get("projectWorkItems");

        assertThat(((Number) order.get("projectId")).longValue()).isEqualTo(10L);
        assertThat(moduleNodes).extracting(row -> ((Number) row.get("id")).longValue()).containsExactlyInAnyOrder(101L, 103L, 104L);
        assertThat(moduleNodes).allSatisfy(row -> assertThat(((Number) row.get("moduleOwnerId")).longValue()).isEqualTo(3L));
        assertThat(projectItems).extracting(row -> ((Number) row.get("id")).longValue()).containsExactly(102L);
        assertThat(projectItems.get(0).get("moduleOwnerId")).isNull();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> stats(long projectId) {
        return f.projects.projects(1, 20).list().stream()
                .filter(row -> ((Number) row.get("id")).longValue() == projectId)
                .map(row -> (Map<String, Object>) row.get("stats")).findFirst().orElseThrow();
    }

    private void rawNode(long id, Long module, String status, Long owner) {
        f.jdbc.update("INSERT INTO nodes(id,project_id,module_id,name,status,owner_id) VALUES(?,10,?,?,?,?)",
                id, module, "无日期" + id, status, owner);
    }
}
