package com.tzme.helpdesk.dto;
import jakarta.validation.constraints.*;
import lombok.Data;
@Data
public class AssetCategoryAssignReq {
    @NotBlank @Size(max=32) private String category;
}
