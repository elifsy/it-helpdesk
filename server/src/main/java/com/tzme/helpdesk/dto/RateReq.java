package com.tzme.helpdesk.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RateReq {
    @NotNull
    @Min(1)
    @Max(5)
    private Integer score;

    private String tags;
    private Boolean solved = Boolean.TRUE;
    private String remark;
}
