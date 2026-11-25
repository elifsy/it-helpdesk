package com.tzme.helpdesk.domain;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TicketEvent {
    private Long id;
    private String ticketNo;
    private String fromStatus;
    private String toStatus;
    private String action;
    private String operator;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Long operatorId;
    private String remark;
    private LocalDateTime createdAt;
}
