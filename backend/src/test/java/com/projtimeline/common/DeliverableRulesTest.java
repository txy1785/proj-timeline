package com.projtimeline.common;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliverableRulesTest {

    @Test
    void statusDefaultsWhenMissing() {
        assertEquals("WANTED", DeliverableRules.status(null, "WANTED"));
        assertEquals("PROVIDED", DeliverableRules.status("  ", "PROVIDED"));
        // 调用方没有给出默认值时空状态非法，不能因为 Set.of(...).contains(null) 抛 NPE
        assertThrows(BusinessException.class, () -> DeliverableRules.status(null, null));
    }

    @Test
    void statusNormalizesCaseAndSpaces() {
        assertEquals("PROVIDED", DeliverableRules.status(" provided ", null));
        assertEquals("WANTED", DeliverableRules.status("wanted", null));
    }

    @Test
    void statusRejectsUnknownValue() {
        assertThrows(BusinessException.class, () -> DeliverableRules.status("DONE", null));
    }

    @Test
    void onlyStatusChangedIgnoresStatusButDetectsOtherEdits() {
        Map<String, Object> before = row("接口文档 v1", 3L, "备注", List.of(9L), 0);
        assertTrue(DeliverableRules.onlyStatusChanged(before, values("接口文档 v1", 3L, "备注", List.of(9L), 0, "PROVIDED")));

        assertFalse(DeliverableRules.onlyStatusChanged(before, values("接口文档 v2", 3L, "备注", List.of(9L), 0, "PROVIDED")));
        assertFalse(DeliverableRules.onlyStatusChanged(before, values("接口文档 v1", 4L, "备注", List.of(9L), 0, "PROVIDED")));
        assertFalse(DeliverableRules.onlyStatusChanged(before, values("接口文档 v1", 3L, "新备注", List.of(9L), 0, "PROVIDED")));
        assertFalse(DeliverableRules.onlyStatusChanged(before, values("接口文档 v1", 3L, "备注", List.of(), 0, "PROVIDED")));
        assertFalse(DeliverableRules.onlyStatusChanged(before, values("接口文档 v1", 3L, "备注", List.of(9L), 1, "PROVIDED")));
    }

    @Test
    void onlyStatusChangedHandlesNullOwnerAndEmptyAttachments() {
        Map<String, Object> before = row("联调记录", null, null, List.of(), 0);
        assertTrue(DeliverableRules.onlyStatusChanged(before, values("联调记录", null, null, List.of(), 0, "PROVIDED")));
        assertFalse(DeliverableRules.onlyStatusChanged(before, values("联调记录", 5L, null, List.of(), 0, "PROVIDED")));
        // before 侧没有关联文件（null）与空集合等价
        Map<String, Object> legacy = row("历史清单", null, null, null, 0);
        assertTrue(DeliverableRules.onlyStatusChanged(legacy, values("历史清单", null, null, List.of(), 0, "PROVIDED")));
    }

    /** 一条交付记录可关联多个文件：新增、删除、换任一元素都算改动，仅顺序不同不算。 */
    @Test
    void onlyStatusChangedComparesAttachmentSetInsteadOfSingleFile() {
        Map<String, Object> before = row("接口文档 v1", 3L, "备注", List.of(9L, 10L), 0);
        assertTrue(DeliverableRules.onlyStatusChanged(before, values("接口文档 v1", 3L, "备注", List.of(10L, 9L), 0, "PROVIDED")));
        assertFalse(DeliverableRules.onlyStatusChanged(before, values("接口文档 v1", 3L, "备注", List.of(9L), 0, "PROVIDED")));
        assertFalse(DeliverableRules.onlyStatusChanged(before, values("接口文档 v1", 3L, "备注", List.of(9L, 10L, 11L), 0, "PROVIDED")));
        assertFalse(DeliverableRules.onlyStatusChanged(before, values("接口文档 v1", 3L, "备注", List.of(9L, 11L), 0, "PROVIDED")));
        assertTrue(DeliverableRules.unchanged(before, values("接口文档 v1", 3L, "备注", List.of(10L, 9L), 0, "WANTED")));
        assertFalse(DeliverableRules.unchanged(before, values("接口文档 v1", 3L, "备注", List.of(9L, 10L, 11L), 0, "WANTED")));
    }

    @Test
    void unchangedAlsoComparesStatus() {
        Map<String, Object> before = row("接口文档 v1", 3L, "备注", List.of(9L), 0);
        assertTrue(DeliverableRules.unchanged(before, values("接口文档 v1", 3L, "备注", List.of(9L), 0, "WANTED")));
        assertFalse(DeliverableRules.unchanged(before, values("接口文档 v1", 3L, "备注", List.of(9L), 0, "PROVIDED")));
    }

    /**
     * 进行中只允许改状态、备注与关联文件：名称、负责人、排序都算改动（交付要求是开工前的承诺）。
     * 已结束更严，备注与关联文件同样锁死，见 {@link DeliverableRules#onlyStatusChanged}。
     */
    @Test
    void onlyProgressAllowedLocksNameOwnerAndSortButAllowsRemarkStatusAndFiles() {
        Map<String, Object> before = row("接口文档 v1", 3L, "备注", List.of(9L), 0);
        assertTrue(DeliverableRules.onlyProgressAllowed(before, values("接口文档 v1", 3L, "新备注", List.of(9L, 10L), 0, "PROVIDED")));
        assertFalse(DeliverableRules.onlyProgressAllowed(before, values("接口文档 v2", 3L, "备注", List.of(9L), 0, "WANTED")));
        assertFalse(DeliverableRules.onlyProgressAllowed(before, values("接口文档 v1", 4L, "备注", List.of(9L), 0, "WANTED")));
        assertFalse(DeliverableRules.onlyProgressAllowed(before, values("接口文档 v1", 3L, "备注", List.of(9L), 1, "WANTED")));
        // 未指定负责人的历史记录：两边都为 null 时不算改派
        Map<String, Object> noOwner = row("联调记录", null, null, List.of(), 0);
        assertTrue(DeliverableRules.onlyProgressAllowed(noOwner, values("联调记录", null, "新备注", List.of(), 0, "WANTED")));
        assertFalse(DeliverableRules.onlyProgressAllowed(noOwner, values("联调记录", 5L, "新备注", List.of(), 0, "WANTED")));
    }

    /** 三档锁是包含关系：进行中放开的备注与关联文件，在已结束上仍然要 409。 */
    @Test
    void progressRuleIsStrictlyLooserThanTheClosedRule() {
        Map<String, Object> before = row("接口文档 v1", 3L, "备注", List.of(9L), 0);
        assertTrue(DeliverableRules.onlyProgressAllowed(before, values("接口文档 v1", 3L, "新备注", List.of(9L), 0, "PROVIDED")));
        assertFalse(DeliverableRules.onlyStatusChanged(before, values("接口文档 v1", 3L, "新备注", List.of(9L), 0, "PROVIDED")));
        assertTrue(DeliverableRules.onlyStatusChanged(before, values("接口文档 v1", 3L, "备注", List.of(9L), 0, "PROVIDED")));
    }

    /**
     * 人工约定（不是机械绑定）：本用例只锁住本地 row() / values() 的键集合，不会自动发现 WorkService 新增的可写列。
     * 给交付物加可写列时，必须同时补 row() / values() 夹具与 `onlyStatusChanged` / `onlyProgressAllowed` 的比较键，
     * 否则「开工后只能改状态/备注/文件」会漏判新增列。
     */
    @Test
    void fixturesCoverEveryManagedKey() {
        assertEquals(Set.of("name", "ownerId", "remark", "attachmentIds", "sortOrder", "status"), row("n", 1L, "r", List.of(2L), 0).keySet());
        assertEquals(Set.of("name", "owner", "remark", "attachments", "sort", "status"), values("n", 1L, "r", List.of(2L), 0, "WANTED").keySet());
    }

    /** before 侧模拟 DataAccess 返回的行：驼峰列名、Long/Integer 混用；关联文件是 id 列表。 */
    private static Map<String, Object> row(String name, Long ownerId, String remark, List<Long> attachmentIds, Integer sortOrder) {
        Map<String, Object> before = new HashMap<>();
        before.put("name", name);
        before.put("ownerId", ownerId);
        before.put("remark", remark);
        before.put("attachmentIds", attachmentIds);
        before.put("sortOrder", sortOrder);
        before.put("status", "WANTED");
        return before;
    }

    /** values 侧模拟写库参数名；sort 用 Integer，便于覆盖 Number 之间的比较。 */
    private static Map<String, Object> values(String name, Long owner, String remark, List<Long> attachments, Integer sort, String status) {
        Map<String, Object> values = new HashMap<>();
        values.put("name", name);
        values.put("owner", owner);
        values.put("remark", remark);
        values.put("attachments", attachments);
        values.put("sort", sort);
        values.put("status", status);
        return values;
    }
}
