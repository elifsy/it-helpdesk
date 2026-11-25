package com.tzme.helpdesk;

import com.tzme.helpdesk.dto.SlaCalendar;
import com.tzme.helpdesk.support.SlaWorkTime;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SlaWorkTimeTest {
    @Test void aDeadlineSkipsEveningsWeekendsAndHolidays() {
        var calendar = new SlaCalendar(List.of(1, 2, 3, 4, 5), "08:00", "17:00",
                List.of(LocalDate.parse("2026-11-30")), true, List.of(1, 2, 4, 8));
        var work = new SlaWorkTime(calendar);
        var friday = LocalDateTime.parse("2026-11-27T16:00");
        var tuesday = LocalDateTime.parse("2026-12-01T09:00");
        assertEquals(tuesday, work.add(friday, 7200));
        assertEquals(7200, work.between(friday, tuesday));
        assertEquals(-7200, work.between(tuesday, friday));
        assertEquals(LocalDateTime.parse("2026-12-01T08:30"), work.add(friday.plusHours(2), 1800));
        assertEquals(friday.plusHours(1), work.add(friday, 3600));
    }
}

