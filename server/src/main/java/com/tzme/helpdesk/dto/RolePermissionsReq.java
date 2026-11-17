package com.tzme.helpdesk.dto;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.*;
@Data
public class RolePermissionsReq {
    @NotNull @Min(0) private Long revision = 0L;
    @NotNull @Min(0) @Max(1) private Integer selfServiceVersion = 0;
    @NotNull private Map<String,List<String>> assignments = new LinkedHashMap<>();
}
