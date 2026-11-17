package com.tzme.helpdesk.domain;

import lombok.Data;

@Data
public class Dept {
    private Long id;
    private String dingDeptId;
    private String name;
    private Long parentId;
    private Integer sort;
}
