package com.tzme.helpdesk.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DispatchReq {
    @NotNull(message = "请选择处理工程师")
    private Long assigneeId;
    private String remark;
}
