package com.tzme.helpdesk.domain;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Ticket {
    private Long id;
    private String no;
    private String title;
    private String description;
    private String type;
    private String cat1;
    private String cat2;
    private String categoryNote;
    private String impact;
    private String workImpact;
    private String priority;
    private String status;
    private String assetNo;
    private Long creatorId;
    private String creatorName;
    private String deptName;
    private String location;
    private Long assigneeId;
    private String assigneeName;
    private String source;
    private String kbHit;
    private Integer triedKb;
    private String solutionCode;
    private String solutionNote;
    private Integer rating;
    private String ratingTags;
    private Integer pausedMin;
    private LocalDateTime createdAt;
    private LocalDateTime acceptedAt;
    private LocalDateTime assignedAt;
    private LocalDateTime dueAt;
    private LocalDateTime finishedAt;
    private LocalDateTime closedAt;
    private LocalDateTime updatedAt;

    /** 非表字段：剩余时限（分钟），由服务层计算 */
    private Long remainMinutes;
    /** 非表字段：是否超时 */
    private Boolean overdue;
    /** 非表字段：同一 IT 的处理队列中排在本单之前的未完成工单数。未派单或已处理完成时为空。 */
    private Long queueAhead;
}
