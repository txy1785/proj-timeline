package com.projtimeline.common;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessCalendarTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-18T04:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @Test
    void dueDateBoundariesUseServerBusinessClock() {
        assertThat(BusinessCalendar.nodeFlags("IN_PROGRESS", LocalDate.of(2026, 10, 18), false, clock))
                .containsEntry("overdue", false).containsEntry("near", true);
        assertThat(BusinessCalendar.nodeFlags("IN_PROGRESS", LocalDate.of(2026, 10, 17), false, clock))
                .containsEntry("overdue", true).containsEntry("near", false);
        assertThat(BusinessCalendar.nodeFlags("NOT_STARTED", LocalDate.of(2026, 10, 21), false, clock))
                .containsEntry("near", true);
        assertThat(BusinessCalendar.nodeFlags("NOT_STARTED", LocalDate.of(2026, 10, 22), false, clock))
                .containsEntry("near", false);
    }

    @Test
    void completedNodesAreNeverMarkedOverdueOrNear() {
        assertThat(BusinessCalendar.nodeFlags("DONE", LocalDate.of(2026, 10, 1), true, clock))
                .containsEntry("overdue", false).containsEntry("near", false).containsEntry("blocked", true);
    }

    @Test
    void everyDayMapsToMondayThroughSundayWeek() {
        LocalDate monday = LocalDate.of(2026, 10, 12);
        for (int offset = 0; offset < 7; offset++) {
            LocalDate[] week = BusinessCalendar.week(monday.plusDays(offset));
            assertThat(week).containsExactly(monday, monday.plusDays(6));
        }
    }
}
