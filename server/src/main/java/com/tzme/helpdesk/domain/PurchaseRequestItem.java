package com.tzme.helpdesk.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 购入申请明细：一行代表一台设备，可以单独走「库存配发 / 采购 / 交付」，互不影响。
 * 状态：PENDING 待处理 → ORDERED 已申请采购（填了 ERP）→ PENDING_PICKUP 待领取（已指定设备）→ COMPLETED 已交付。
 */
@Data
public class PurchaseRequestItem {

    private Long id;
    private Long requestId;
    private Long catalogId;
    private String itemName;
    private String category;
    private String spec;
    private String status;
    private String erpNo;
    private LocalDateTime orderedAt;
    private String assetNo;
    private String deliveredBy;
    private LocalDateTime deliveredAt;
    private String remark;
    private LocalDateTime createdAt;

    /** 非表字段：配发设备的名称，列表展示用 */
    private String assetName;
}
