package com.tzme.helpdesk.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;

public record SlaCalendar(
        @NotEmpty @Size(max=7) List<@NotNull @Min(1) @Max(7) Integer> weekdays,
        @NotBlank @Pattern(regexp="(?:[01]\\d|2[0-3]):[0-5]\\d") String start,
        @NotBlank @Pattern(regexp="(?:[01]\\d|2[0-3]):[0-5]\\d") String end,
        @NotNull @Size(max=366) List<@NotNull LocalDate> holidays,
        @NotNull Boolean pauseForInfo,
        @NotNull @Size(min=4,max=4) List<@NotNull @Min(1) @Max(8760) Integer> responseHours) {
    public static SlaCalendar defaults() {
        return new SlaCalendar(List.of(1,2,3,4,5,6), "08:00", "17:00", List.of(), true, List.of(1,2,4,8));
    }
}

