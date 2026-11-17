package com.tzme.helpdesk.domain;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class Asset {
    private Long id;
    private String no;
    private String name;
    private String model;
    private String category;
    private String sn;
    private String userName;
    private String location;
    private LocalDate buyDate;
    private String warranty;
    private LocalDate warrantyEnd;
    private String status;
    private String borrower;
    private LocalDate borrowDue;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
