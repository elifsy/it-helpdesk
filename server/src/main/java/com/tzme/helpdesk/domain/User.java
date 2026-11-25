package com.tzme.helpdesk.domain;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

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
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String badgeAvatar;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private List<List<String>> namePinyin;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
