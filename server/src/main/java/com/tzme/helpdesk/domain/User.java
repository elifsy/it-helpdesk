package com.tzme.helpdesk.domain;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class User {
    private Long id;
    private String dingUserId;
    private String name;
    private String employeeNo;
    private Long deptId;
    private String deptName;
    private String mobile;
    private String email;
    private String role;
    private String skill;
    private String location;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
