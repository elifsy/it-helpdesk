package com.tzme.helpdesk.dto;
import jakarta.validation.constraints.*;
import lombok.Data;
@Data
public class DeptAdminReq {
    @NotBlank @Size(max=64) private String name;
    @NotNull @Min(0) private Long parentId = 0L;
    @NotNull @Min(0) @Max(9999) private Integer sort = 0;
}
