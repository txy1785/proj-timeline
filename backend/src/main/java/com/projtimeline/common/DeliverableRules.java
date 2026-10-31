package com.projtimeline.common;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 工作项交付物的纯规则：状态归一化，以及「除状态外是否真的改动」的判定。
 * <p>
 * 放在 common 是因为它不依赖数据库、需要单测覆盖；WorkService 只负责取数与落库。
 * before 使用 DataAccess 的驼峰列名（name/ownerId/remark/attachmentIds/sortOrder），
 * values 使用写库参数名（name/owner/remark/attachments/sort），两套键名在此集中对齐。
 * 关联文件按集合比较：一条交付记录可关联多个文件，新增/删除/换才算变化，仅顺序不同不算。
 */
public final class DeliverableRules {
    public static final String WANTED = "WANTED";
    public static final String PROVIDED = "PROVIDED";

    private DeliverableRules() {}

    /** 归一化状态：大小写与空值宽容，非法值抛 4000；编辑时未传字段用 fallback 保留原值。 */
    public static String status(Object raw, String fallback) {
        String value = raw == null || raw.toString().isBlank() ? fallback : raw.toString().trim().toUpperCase(Locale.ROOT);
        // fallback 也可能为空（调用方没有给出默认值），此时按非法值处理，不能落到 Set.of(...).contains(null)
        if (value == null || !Set.of(WANTED, PROVIDED).contains(value)) {
            throw BusinessException.badRequest("交付物状态只能是 WANTED 或 PROVIDED");
        }
        return value;
    }

    /** 除 status 外的字段是否完全未变：用于拒绝「已完成/已取消工作项」上的改名、改派、换文件、改备注与改排序。 */
    public static boolean onlyStatusChanged(Map<String, Object> before, Map<String, Object> values) {
        return Objects.equals(before.get("name"), values.get("name"))
                && sameNumber(before.get("ownerId"), values.get("owner"))
                && Objects.equals(before.get("remark"), values.get("remark"))
                && sameIds(before.get("attachmentIds"), values.get("attachments"))
                && sameNumber(before.get("sortOrder"), values.get("sort"));
    }

    /** 是否完全没有任何变化：整表回传未改动的字段时不写日志，保持与任务状态接口一致的幂等。 */
    public static boolean unchanged(Map<String, Object> before, Map<String, Object> values) {
        return onlyStatusChanged(before, values) && Objects.equals(before.get("status"), values.get("status"));
    }

    private static boolean sameNumber(Object left, Object right) {
        Long a = number(left);
        Long b = number(right);
        return Objects.equals(a, b);
    }

    /** 关联文件 id 集合比较：忽略顺序与重复，新增/删除/换任一元素即为变化。 */
    private static boolean sameIds(Object left, Object right) {
        return ids(left).equals(ids(right));
    }

    private static Set<Long> ids(Object value) {
        if (!(value instanceof Collection<?> items)) return Set.of();
        Set<Long> result = new HashSet<>();
        for (Object item : items) {
            Long id = number(item);
            if (id != null) result.add(id);
        }
        return result;
    }

    private static Long number(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        String text = value.toString().trim();
        if (text.isEmpty()) return null;
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
