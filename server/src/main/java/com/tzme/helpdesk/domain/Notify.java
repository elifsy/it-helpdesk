package com.tzme.helpdesk.domain;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Notify {
    private Long id;
    private String toUser;
    private String title;
    private String content;
    private String type;
    private String refNo;
    private Integer isRead;
    private LocalDateTime createdAt;
}
