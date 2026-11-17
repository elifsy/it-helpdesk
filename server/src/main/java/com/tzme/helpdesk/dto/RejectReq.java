package com.tzme.helpdesk.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RejectReq {
    @NotBlank(message = "请填写驳回原因")
    private String reason;
}
