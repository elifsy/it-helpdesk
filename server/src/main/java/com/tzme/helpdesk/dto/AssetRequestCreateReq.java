package com.tzme.helpdesk.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class AssetRequestCreateReq {
    @NotBlank(message = "请指定申请类型")
    private String type;

    @NotBlank(message = "请选择设备")
    private String assetNo;
    @Size(max=32) private String category;

    @NotBlank(message = "请填写申请事由")
    @Size(min = 4, max = 500, message = "申请事由至少 4 个字")
    private String reason;

    /** 借用时必填 */
    private LocalDate expectReturn;
}
