package com.tzme.helpdesk.domain;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class AssetRequest {
    private Long id;
    private String no;
    private String type;
    private String assetNo;
    private String applicant;
    private String dept;
    private String reason;
    private LocalDate expectReturn;
    private String status;
    private String approver;
    private LocalDateTime handledAt;
    private String newAssetNo;
    private String remark;
    private LocalDateTime createdAt;

    /** 非表字段：设备名称，列表展示用 */
    private String assetName;
    private String assetCategory;
}
