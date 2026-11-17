package com.tzme.helpdesk.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RecordReq {
    private String kind = "REMOTE";

    @NotBlank(message = "请填写处理内容")
    private String content;

    private Integer costMin = 0;
}
