package com.tzme.helpdesk.domain;

import com.tzme.helpdesk.dto.NotificationAction;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Notify {
    private Long id;
    private String toUser;
    private Long recipientId;
    private String title;
    private String content;
    private String type;
    private String refNo;
    private Integer isRead;
    private LocalDateTime createdAt;
    private NotificationAction action;
}
