package com.projtimeline.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 进度口径：工作项是最小交付单位，只有确认完成才为100，不估算进行中百分比。
 * 模块/项目进度是已交付数除以未取消工作项数，见 ProjectService。
 * 阶段性目标的量化进度单独由 {@link #metricProgress} 计算，不参与工作项聚合。
 */
public final class ProgressRules {
    private ProgressRules() {}

    /** Task counts remain in the signature for old callers; a feature is delivered only by DONE. */
    public static int nodeProgress(String status, int totalTasks, int doneTasks) {
        return "DONE".equals(status) ? 100 : 0;
    }

    /** Delivery rate among non-cancelled work items; old task-derived sums are deliberately ignored. */
    public static int aggregate(int activeNodes, int progressSum, int doneNodes) {
        if (activeNodes <= 0) return 0;
        int percent = Math.round(100f * Math.max(0, Math.min(activeNodes, doneNodes)) / activeNodes);
        return doneNodes < activeNodes ? Math.min(99, percent) : 100;
    }

    /**
     * 阶段性目标的量化进度：当前值 ÷ 目标值，四舍五入后夹在 0..100。
     * 超额完成按 100 显示（不出现 120%），负向偏离按 0；目标值缺失或 ≤0 时返回 0（调用方只在有指标时使用）。
     */
    public static int metricProgress(BigDecimal current, BigDecimal target) {
        if (target == null || target.signum() <= 0) return 0;
        BigDecimal value = current == null ? BigDecimal.ZERO : current;
        if (value.signum() <= 0) return 0;
        // 先比较再相除：超大比值（如 3e9 / 100）乘 100 后会溢出 int，先返回 100 避免算出 0%
        if (value.compareTo(target) >= 0) return 100;
        return value.multiply(BigDecimal.valueOf(100)).divide(target, 0, RoundingMode.HALF_UP).intValue();
    }
}
