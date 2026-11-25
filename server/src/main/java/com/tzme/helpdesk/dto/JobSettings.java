package com.tzme.helpdesk.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record JobSettings(
        @NotNull @Min(0) Long revision,
        @NotNull Boolean slaScanEnabled,
        @NotBlank @Size(max = 120) String slaCron,
        @NotNull Boolean contactSyncEnabled,
        @NotBlank @Size(max = 120) String contactCron,
        @Min(1) @Max(720) Integer slaReminderHours,
        @Min(1) @Max(720) Integer confirmationReminderHours) {
    public static final String DEFAULT_SLA_CRON = "0 */10 * * * ?";
    public static final String DEFAULT_CONTACT_CRON = "0 0 2 * * ?";
    public static final int DEFAULT_SLA_REMINDER_HOURS = 4;
    public static final int DEFAULT_CONFIRMATION_REMINDER_HOURS = 24;
}

