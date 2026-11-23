package com.tzme.helpdesk.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;

public record IncomingNotificationRules(
        @NotNull @Min(0) Long revision,
        @NotNull @Size(max=120) List<@NotNull @Valid TicketRule> ticketRules,
        @NotNull @Size(max=4) Map<String, @NotNull @Size(min=1,max=20) List<@NotNull @Positive Long>> assetRecipients) {

    public record TicketRule(@NotBlank @Size(max=32) String type,
                             @NotBlank @Size(max=32) String cat1,
                             @Size(max=32) String cat2,
                             @NotNull @Size(min=1,max=20) List<@NotNull @Positive Long> recipientIds) { }

    public static IncomingNotificationRules defaults() {
        return new IncomingNotificationRules(0L, List.of(), Map.of());
    }
}
