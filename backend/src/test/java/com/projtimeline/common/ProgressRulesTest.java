package com.projtimeline.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ProgressRulesTest {
    @Test
    void unfinishedFeaturesDoNotInventAPercentage() {
        assertThat(ProgressRules.nodeProgress("IN_PROGRESS", 4, 1)).isZero();
        assertThat(ProgressRules.nodeProgress("IN_PROGRESS", 3, 2)).isZero();
        assertThat(ProgressRules.nodeProgress("IN_PROGRESS", 5, 5)).isZero();
        assertThat(ProgressRules.nodeProgress("NOT_STARTED", 3, 0)).isZero();
    }

    @Test
    void legacyTaskCountsCannotMarkFeatureDelivered() {
        assertThat(ProgressRules.nodeProgress("IN_PROGRESS", 200, 199)).isZero();
        assertThat(ProgressRules.nodeProgress("IN_PROGRESS", 200, 1)).isZero();
        assertThat(ProgressRules.nodeProgress("IN_PROGRESS", 3, 1)).isZero();
    }

    @Test
    void doneNodeAlwaysReadsFull() {
        assertThat(ProgressRules.nodeProgress("DONE", 3, 1)).isEqualTo(100);
        assertThat(ProgressRules.nodeProgress("DONE", 0, 0)).isEqualTo(100);
    }

    @Test
    void tasklessFeatureNeedsExplicitDelivery() {
        assertThat(ProgressRules.nodeProgress("NOT_STARTED", 0, 0)).isZero();
        assertThat(ProgressRules.nodeProgress("IN_PROGRESS", 0, 0)).isZero();
    }

    @Test
    void aggregateNeverShowsFullWhileNodesAreOpen() {
        assertThat(ProgressRules.aggregate(2, 199, 1)).isEqualTo(50);
        assertThat(ProgressRules.aggregate(2, 200, 2)).isEqualTo(100);
        assertThat(ProgressRules.aggregate(3, 100, 1)).isEqualTo(33);
        assertThat(ProgressRules.aggregate(0, 0, 0)).isZero();
    }

    @Test
    void cancelledNodeDoesNotContributeProgress() {
        assertThat(ProgressRules.nodeProgress("CANCELLED", 2, 2)).isZero();
    }

    @Test
    void stageTargetMetricProgressUsesCurrentOverTarget() {
        assertThat(ProgressRules.metricProgress(new BigDecimal("2"), new BigDecimal("4"))).isEqualTo(50);
        assertThat(ProgressRules.metricProgress(new BigDecimal("1"), new BigDecimal("3"))).isEqualTo(33);
        assertThat(ProgressRules.metricProgress(new BigDecimal("3"), new BigDecimal("3"))).isEqualTo(100);
        assertThat(ProgressRules.metricProgress(new BigDecimal("2"), new BigDecimal("3"))).isEqualTo(67);
    }

    @Test
    void stageTargetMetricProgressClampsAndHandlesMissingValues() {
        // 超额完成按 100 显示，不出现 150%；负向偏离按 0
        assertThat(ProgressRules.metricProgress(new BigDecimal("5"), new BigDecimal("3"))).isEqualTo(100);
        assertThat(ProgressRules.metricProgress(new BigDecimal("-1"), new BigDecimal("3"))).isZero();
        // 当前值缺省按 0；目标值缺失或非正时返回 0，避免除零
        assertThat(ProgressRules.metricProgress(null, new BigDecimal("3"))).isZero();
        assertThat(ProgressRules.metricProgress(new BigDecimal("1"), null)).isZero();
        assertThat(ProgressRules.metricProgress(new BigDecimal("1"), BigDecimal.ZERO)).isZero();
        // 小数目标同样按四舍五入
        assertThat(ProgressRules.metricProgress(new BigDecimal("2.5"), new BigDecimal("4"))).isEqualTo(63);
    }

    @Test
    void stageTargetMetricProgressDoesNotOverflowOnHugeRatios() {
        // 先比较再相除：3e9 / 100 与 2^32 级别的比值都按 100 显示，不能因为 int 溢出算出 0
        assertThat(ProgressRules.metricProgress(new BigDecimal("3000000000"), new BigDecimal("100"))).isEqualTo(100);
        assertThat(ProgressRules.metricProgress(new BigDecimal("10737418.24"), new BigDecimal("0.25"))).isEqualTo(100);
        assertThat(ProgressRules.metricProgress(new BigDecimal("0.5"), new BigDecimal("1"))).isEqualTo(50);
    }
}
