package com.projtimeline.common;

/**
 * 进度口径：节点进度不再手填，由任务完成率实时派生；没有任务的节点按状态估算。
 * 模块/项目进度仍是对未取消节点进度做算术平均，见 ProjectService。
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
}
