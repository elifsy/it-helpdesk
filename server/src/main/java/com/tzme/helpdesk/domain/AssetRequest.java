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
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Long applicantId;
    private PendingReportCounts pendingByType;

    public Long getPendingReportCount() {
        return pendingByType == null ? null : pendingByType.getRepair();
    }
    private String dept;
    private String reason;
    private LocalDate expectReturn;
    private String status;
    private String approver;
    private LocalDateTime handledAt;
    private String newAssetNo;
    private String remark;
    /** 钉钉审批：实例 id / 最近同步到的状态 / 同步时间 / 结果摘要 */
    private String dingInstanceId;
    private String dingStatus;
    private LocalDateTime dingSyncedAt;
    private String dingMessage;
    /** 交接状态：PICKUP 待领取 / RETURN 待归还；为空表示审批通过即执行 */
    private String handoverStatus;
    private String handoverBy;
    private LocalDateTime handoverAt;
    private LocalDateTime createdAt;

    /** 非表字段：设备名称，列表展示用 */
    private String assetName;
    private String assetCategory;
}
