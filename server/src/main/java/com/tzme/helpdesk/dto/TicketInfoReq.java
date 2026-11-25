package com.tzme.helpdesk.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TicketInfoReq(@NotBlank @Size(max=400) String note) { }

