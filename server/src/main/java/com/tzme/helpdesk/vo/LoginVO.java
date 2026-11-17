package com.tzme.helpdesk.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class LoginVO {
    private String token;
    private Long id;
    private String name;
    private String deptName;
    private String role;
    private List<String> roles;
    private String dingUserId;
    private List<String> permissions;
}
