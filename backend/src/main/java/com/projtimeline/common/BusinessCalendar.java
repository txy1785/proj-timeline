package com.projtimeline.common;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.Set;

public final class BusinessCalendar {
    private static final Set<String> OPEN_NODE_STATUSES = Set.of("NOT_STARTED", "IN_PROGRESS");

    private BusinessCalendar() {}

    public static Map<String, Boolean> nodeFlags(String status, LocalDate planEnd, boolean blocked, Clock clock) {
        LocalDate today = LocalDate.now(clock);
        boolean open = OPEN_NODE_STATUSES.contains(status);
        boolean overdue = open && planEnd != null && planEnd.isBefore(today);
        boolean near = open && planEnd != null && !planEnd.isBefore(today) && !planEnd.isAfter(today.plusDays(3));
        return Map.of("overdue", overdue, "near", near, "blocked", blocked);
    }

    public static LocalDate[] week(LocalDate date) {
        LocalDate start = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return new LocalDate[]{start, start.plusDays(6)};
    }
}
