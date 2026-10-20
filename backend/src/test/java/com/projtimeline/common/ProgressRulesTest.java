package com.projtimeline.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProgressRulesTest {
    @Test
    void progressComesFromFinishedTaskRatio() {
        assertThat(ProgressRules.nodeProgress("IN_PROGRESS", 4, 1)).isEqualTo(25);
        assertThat(ProgressRules.nodeProgress("IN_PROGRESS", 3, 2)).isEqualTo(67);
        assertThat(ProgressRules.nodeProgress("IN_PROGRESS", 5, 5)).isEqualTo(100);
        assertThat(ProgressRules.nodeProgress("NOT_STARTED", 3, 0)).isZero();
    }

    @Test
    void roundingNeverShowsFullWhileTasksRemain() {
        assertThat(ProgressRules.nodeProgress("IN_PROGRESS", 200, 199)).isEqualTo(99);
        assertThat(ProgressRules.nodeProgress("IN_PROGRESS", 200, 1)).isEqualTo(1);
        assertThat(ProgressRules.nodeProgress("IN_PROGRESS", 3, 1)).isEqualTo(33);
    }

    @Test
    void doneNodeAlwaysReadsFull() {
        assertThat(ProgressRules.nodeProgress("DONE", 3, 1)).isEqualTo(100);
        assertThat(ProgressRules.nodeProgress("DONE", 0, 0)).isEqualTo(100);
    }

    @Test
    void tasklessNodeFallsBackToStatusInsteadOfManualValue() {
        assertThat(ProgressRules.nodeProgress("NOT_STARTED", 0, 0)).isZero();
        assertThat(ProgressRules.nodeProgress("IN_PROGRESS", 0, 0)).isEqualTo(50);
    }

    @Test
    void aggregateNeverShowsFullWhileNodesAreOpen() {
        assertThat(ProgressRules.aggregate(2, 199, 1)).isEqualTo(99);
        assertThat(ProgressRules.aggregate(2, 200, 2)).isEqualTo(100);
        assertThat(ProgressRules.aggregate(3, 100, 1)).isEqualTo(33);
        assertThat(ProgressRules.aggregate(0, 0, 0)).isZero();
    }

    @Test
    void cancelledNodeDoesNotContributeProgress() {
        assertThat(ProgressRules.nodeProgress("CANCELLED", 2, 2)).isZero();
    }
}
