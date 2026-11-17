package com.tzme.helpdesk.dto;
import jakarta.validation.constraints.*;
import lombok.Data;
@Data
public class AssetCategoryReq {
    @NotBlank @Size(max=32) private String name;
    @NotNull @Min(0) @Max(9999) private Integer sort=0;
}
