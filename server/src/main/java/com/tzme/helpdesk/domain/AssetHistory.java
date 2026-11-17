package com.tzme.helpdesk.domain;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AssetHistory {
    private Long id;
    private String assetNo;
    private String action;
    private String detail;
    private String operator;
    private LocalDateTime createdAt;
}
