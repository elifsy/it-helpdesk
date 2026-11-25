package com.tzme.helpdesk.dto;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.List;
@Data
public class UserAdminReq {
    @NotBlank @Pattern(regexp="USER|IT|ADMIN|VIEWER|SUPER_ADMIN") private String role;
    @NotBlank @Pattern(regexp="ACTIVE|DISABLED") private String status;
    @NotNull @Min(0) private Long deptId;
    @Size(max=128) private String skill;
    @Size(max=64) private String location;
    private Long handoverId;
}
