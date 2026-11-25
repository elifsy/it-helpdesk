package com.tzme.helpdesk.support;

import com.tzme.helpdesk.dto.SlaCalendar;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;

public final class SlaWorkTime {
    private final Set<Integer> weekdays;
    private final Set<LocalDate> holidays;
    private final LocalTime start;
    private final LocalTime end;

    public SlaWorkTime(SlaCalendar calendar) {
        weekdays = Set.copyOf(calendar.weekdays());
        holidays = Set.copyOf(calendar.holidays());
        start = LocalTime.parse(calendar.start());
        end = LocalTime.parse(calendar.end());
        if (weekdays.isEmpty() || !start.isBefore(end)) throw new IllegalArgumentException("Invalid SLA work calendar");
    }

    public LocalDateTime add(LocalDateTime from, long seconds) {
        if (seconds <= 0) return from;
        for (LocalDate day = from.toLocalDate(); ; day = day.plusDays(1)) {
            if (!working(day)) continue;
            LocalDateTime first = later(from, day.atTime(start));
            long available = Duration.between(first, day.atTime(end)).getSeconds();
            if (available <= 0) continue;
            if (available >= seconds) return first.plusSeconds(seconds);
            seconds -= available;
        }
    }

    public long between(LocalDateTime from, LocalDateTime to) {
        if (to.isBefore(from)) return -between(to, from);
        long work = 0;
        for (LocalDate day = from.toLocalDate(); !day.isAfter(to.toLocalDate()); day = day.plusDays(1)) {
            if (working(day)) work += Math.max(0, Duration.between(later(from, day.atTime(start)), earlier(to, day.atTime(end))).getSeconds());
        }
        return work;
    }

    private boolean working(LocalDate date) {
        return weekdays.contains(date.getDayOfWeek().getValue()) && !holidays.contains(date);
    }

    private static LocalDateTime later(LocalDateTime a, LocalDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDateTime earlier(LocalDateTime a, LocalDateTime b) {
        return a.isBefore(b) ? a : b;
    }
}

