package com.tzme.helpdesk.domain;

import lombok.Data;

@Data
public class SolutionCode {
    private Long id;
    private String category;
    private String code;
    private String name;
    private Integer sort;
}
