package com.projtimeline.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 进度口径：节点进度不再手填，由任务完成率实时派生；没有任务的节点按状态估算。
 * 模块/项目进度仍是对未取消节点进度做算术平均，见 ProjectService。
 * 阶段性目标的量化进度单独由 {@link #metricProgress} 计算，不参与节点聚合。
 */
public final class ProgressRules {
    private ProgressRules() {}

    public static int nodeProgress(String status, int totalTasks, int doneTasks) {
        if ("DONE".equals(status)) return 100;
        if ("CANCELLED".equals(status)) return 0;
        if (totalTasks <= 0) return "IN_PROGRESS".equals(status) ? 50 : 0;
        int percent = Math.round(doneTasks * 100f / totalTasks);
        // 四舍五入不能让「还有任务没做完」显示成 100%，例如 199/200 会进位到 100
        return doneTasks < totalTasks && percent >= 100 ? 99 : percent;
    }

    /** 模块/项目进度：未取消节点进度的算术平均；只要还有节点没完成，平均值也封顶 99。 */
    public static int aggregate(int activeNodes, int progressSum, int doneNodes) {
        if (activeNodes <= 0) return 0;
        int percent = Math.round((float) progressSum / activeNodes);
        return doneNodes < activeNodes && percent >= 100 ? 99 : percent;
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
