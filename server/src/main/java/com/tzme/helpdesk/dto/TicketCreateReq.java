package com.tzme.helpdesk.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TicketCreateReq {
    @NotBlank(message = "请填写问题标题")
    @Size(max = 200)
    private String title;

    @NotBlank(message = "请描述具体问题")
    @Size(max = 4000)
    private String description;

    @NotBlank(message = "请填写故障位置，例如楼栋、楼层和房间/工位")
    @Size(max = 64, message = "故障位置最多填写 64 字")
    private String location;

    private String type = "INCIDENT";

    @NotBlank(message = "请选择问题分类")
    private String cat1;

    private String cat2;

    @Size(max = 200, message = "分类补充最多填写 200 字")
    private String categoryNote;

    private String impact = "SELF";
    private String workImpact = "SLOW";
    private String assetNo;
    private String kbHit;
    private Integer triedKb = 0;
    private String source = "钉钉工作台";
}
