package com.tzme.helpdesk.dto;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class KbRevisionReq {
    @NotNull @Min(0) private Integer revision;
}
