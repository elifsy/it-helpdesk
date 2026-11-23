package com.tzme.helpdesk.domain;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class Asset {
    private Long id;
    private String no;
    private String erpNo;
    private String name;
    private String model;
    private String category;
    private String sn;
    private String userName;
    private String location;
    private LocalDate buyDate;
    private String warranty;
    private LocalDate warrantyEnd;
    private String status;
    private String borrower;
    private LocalDate borrowDue;
    private String remark;
    /** 卡片图标 key（为空时按分类与名称自动匹配） */
    private String icon;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 非表字段：被「已批准、待交接」的申请占用时，记录占用人与单号，供列表标记与选择器过滤 */
    private String reservedBy;
    private String reservedRequestNo;
}
