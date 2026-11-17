package com.tzme.helpdesk.domain;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Attachment {
    private Long id;
    private String bizType;
    private String bizNo;
    private String fileName;
    private String storedName;
    private String contentType;
    private Long fileSize;
    private String uploader;
    private LocalDateTime createdAt;
}
