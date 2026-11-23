package com.tzme.helpdesk.domain;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class MissingReport {
    private String no;
    private Long requesterId;
    private String requesterName;
    private String recordedBy;
    private String title;
    private String description;
    private String channel;
    private LocalDateTime completedAt;
    private String status;
    private String ticketNo;
    private String cancelReason;
    private LocalDateTime createdAt;
}
