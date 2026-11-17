package com.tzme.helpdesk.dto;
import jakarta.validation.constraints.*;
import lombok.Data;
@Data
public class SolutionAdminReq {
    @NotBlank @Size(max=32) private String category;
    @NotBlank @Size(max=64) private String code;
    @NotBlank @Size(max=64) private String name;
    @NotNull @Min(0) @Max(9999) private Integer sort = 0;
}
