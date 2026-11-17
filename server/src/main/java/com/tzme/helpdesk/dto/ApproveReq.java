package com.tzme.helpdesk.dto;

import lombok.Data;

@Data
public class ApproveReq {
    /** 更换申请时必填：替换的新资产编号 */
    private String newAssetNo;
    @jakarta.validation.constraints.Size(max=32) private String newAssetCategory;
    private String remark;
}
