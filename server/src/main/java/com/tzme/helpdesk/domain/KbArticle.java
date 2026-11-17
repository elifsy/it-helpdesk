package com.tzme.helpdesk.domain;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KbArticle {
    private Long id;
    private String title;
    private String category;
    private String summary;
    private String content;
    private Integer views;
    private Integer helpful;
    private Integer notHelpful;
    private String status;
    private String sourceTicketNo;
    private Integer revision;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
