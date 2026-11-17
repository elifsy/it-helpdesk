package com.tzme.helpdesk.dto;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class KbCaptureReq {
    @NotBlank(message="请填写已关闭工单号") @Size(max=32) private String ticketNo;
}
