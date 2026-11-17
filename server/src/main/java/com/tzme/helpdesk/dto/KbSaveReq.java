package com.tzme.helpdesk.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class KbSaveReq {
    @NotNull @Min(0) private Integer revision;
    @NotBlank(message="请填写知识标题") @Size(max=200) private String title;
    @NotBlank(message="请填写知识分类") @Size(max=32) private String category;
    @NotBlank(message="请填写摘要") @Size(max=500) private String summary;
    @NotBlank(message="请填写处理步骤") @Size(max=20000) private String content;
}
