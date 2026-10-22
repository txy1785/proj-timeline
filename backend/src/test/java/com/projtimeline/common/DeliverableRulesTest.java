package com.projtimeline.common;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
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
        Map<String, Object> before = row("接口文档 v1", 3L, "备注", 9L, 0);
        assertTrue(DeliverableRules.onlyStatusChanged(before, values("接口文档 v1", 3L, "备注", 9L, 0, "PROVIDED")));

        assertFalse(DeliverableRules.onlyStatusChanged(before, values("接口文档 v2", 3L, "备注", 9L, 0, "PROVIDED")));
        assertFalse(DeliverableRules.onlyStatusChanged(before, values("接口文档 v1", 4L, "备注", 9L, 0, "PROVIDED")));
        assertFalse(DeliverableRules.onlyStatusChanged(before, values("接口文档 v1", 3L, "新备注", 9L, 0, "PROVIDED")));
        assertFalse(DeliverableRules.onlyStatusChanged(before, values("接口文档 v1", 3L, "备注", null, 0, "PROVIDED")));
        assertFalse(DeliverableRules.onlyStatusChanged(before, values("接口文档 v1", 3L, "备注", 9L, 1, "PROVIDED")));
    }

    @Test
    void onlyStatusChangedHandlesNullOwnerAndAttachment() {
        Map<String, Object> before = row("联调记录", null, null, null, 0);
        assertTrue(DeliverableRules.onlyStatusChanged(before, values("联调记录", null, null, null, 0, "PROVIDED")));
        assertFalse(DeliverableRules.onlyStatusChanged(before, values("联调记录", 5L, null, null, 0, "PROVIDED")));
    }

    @Test
    void unchangedAlsoComparesStatus() {
        Map<String, Object> before = row("接口文档 v1", 3L, "备注", 9L, 0);
        assertTrue(DeliverableRules.unchanged(before, values("接口文档 v1", 3L, "备注", 9L, 0, "WANTED")));
        assertFalse(DeliverableRules.unchanged(before, values("接口文档 v1", 3L, "备注", 9L, 0, "PROVIDED")));
    }

    /**
     * 契约守卫：`onlyStatusChanged` 只比较约定键，两边键名必须与 WorkService 的 UPDATE 可写列一一对应。
     * 将来给交付物加可写列时，这里和规则方法都要补键，否则「已完成/已取消节点只能改状态」会漏判新增列。
     */
    @Test
    void fixturesCoverEveryManagedKey() {
        assertEquals(Set.of("name", "ownerId", "remark", "attachmentId", "sortOrder", "status"), row("n", 1L, "r", 2L, 0).keySet());
        assertEquals(Set.of("name", "owner", "remark", "attachment", "sort", "status"), values("n", 1L, "r", 2L, 0, "WANTED").keySet());
    }

    /** before 侧模拟 DataAccess 返回的行：驼峰列名、Long/Integer 混用。 */
    private static Map<String, Object> row(String name, Long ownerId, String remark, Long attachmentId, Integer sortOrder) {
        Map<String, Object> before = new HashMap<>();
        before.put("name", name);
        before.put("ownerId", ownerId);
        before.put("remark", remark);
        before.put("attachmentId", attachmentId);
        before.put("sortOrder", sortOrder);
        before.put("status", "WANTED");
        return before;
    }

    /** values 侧模拟写库参数名；sort 用 Integer，便于覆盖 Number 之间的比较。 */
    private static Map<String, Object> values(String name, Long owner, String remark, Long attachment, Integer sort, String status) {
        Map<String, Object> values = new HashMap<>();
        values.put("name", name);
        values.put("owner", owner);
        values.put("remark", remark);
        values.put("attachment", attachment);
        values.put("sort", sort);
        values.put("status", status);
        return values;
    }
}
