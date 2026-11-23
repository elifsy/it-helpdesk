package com.tzme.helpdesk.domain;

import lombok.Data;

import java.time.LocalDateTime;

/** 可申请购买的设备目录（由 IT/管理员维护） */
@Data
public class PurchaseCatalog {
    private Long id;
    private String name;
    private String category;
    private String spec;
    private String remark;
    /** 卡片图标 key（见前端 deviceIcons），为空时按分类自动匹配 */
    private String icon;
    private Integer enabled;
    private Integer sort;
    private LocalDateTime createdAt;
}
