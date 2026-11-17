package com.tzme.helpdesk.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class FinishReq {
    /** 关单时确认的问题分类（一级），统计与知识沉淀以此为准 */
    @NotBlank(message = "请选择问题分类")
    @Size(max = 32)
    private String cat1;

    /** 二级分类，可空 */
    @Size(max = 32)
    private String cat2;

    @NotBlank(message = "请填写处理结论")
    @Size(max = 500)
    private String note;
}
