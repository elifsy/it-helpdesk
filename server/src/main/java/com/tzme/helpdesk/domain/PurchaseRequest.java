package com.tzme.helpdesk.domain;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 设备购入申请。
 * 状态：PENDING 待审批 → APPROVED 已批准待采购 → ORDERED 已申请采购（有 ERP 单号）
 * → PENDING_PICKUP 已入库待领取 → COMPLETED 已完成；REJECTED / CANCELLED 结束。
 */
@Data
public class PurchaseRequest {
    private Long id;
    private String no;
    private Long catalogId;
    private String itemName;
    private String category;
    private String applicant;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Long applicantId;
    private PendingReportCounts pendingByType;

    public Long getPendingReportCount() {
        return pendingByType == null ? null : pendingByType.getRepair();
    }
    private String dept;
    private String reason;
    private LocalDate expectDate;
    private String status;
    private String approver;
    private LocalDateTime handledAt;
    private String erpNo;
    private LocalDateTime orderedAt;
    private String assetNo;
    private String handoverBy;
    private LocalDateTime handoverAt;
    private String remark;
    private LocalDateTime createdAt;

    /* 钉钉审批（轮询同步） */
    private String dingInstanceId;
    private String dingStatus;
    private LocalDateTime dingSyncedAt;
    private String dingMessage;

    /** 非表字段：把资产名称带出来展示 */
    private String assetName;

    /** 明细（购物车里的每一项，一行一台设备） */
    private List<PurchaseRequestItem> items;
    private Integer itemCount;
    private Integer doneCount;
    /** 明细摘要，如「联想 ThinkPad T14s 等 3 项 / 共 4 台」 */
    private String itemSummary;
}
