package com.tzme.helpdesk.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.time.LocalDate;

/** 新增和编辑共用的资产资料；领用、借用关系由申请流程维护。 */
@Data
public class AssetFieldsReq {
    @Size(max=32, message="ERP资产编号最多填写32字") private String erpNo;
    @NotBlank @Size(max=128) private String name;
    @Size(max=128, message="型号最多填写128字") private String model;
    @NotBlank @Size(max=32) private String category;
    @Size(max=64) private String sn;
    @Size(max=64) private String location;
    private LocalDate buyDate;
    @Size(max=32) private String warranty;
    private LocalDate warrantyEnd;
    @NotBlank @Pattern(regexp="在用|闲置|借出中|维修中|待报废|已报废|待入库") private String status;
    @Size(max=255) private String remark;
    /** 卡片图标 key；空或 auto 表示按分类与名称自动匹配 */
    @Size(max=32) private String icon;
}
