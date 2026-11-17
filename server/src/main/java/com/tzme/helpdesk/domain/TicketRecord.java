package com.tzme.helpdesk.domain;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TicketRecord {
    private Long id;
    private String ticketNo;
    private String kind;
    private String content;
    private Integer costMin;
    private String operator;
    private LocalDateTime createdAt;
}
