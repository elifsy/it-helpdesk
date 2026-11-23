package com.tzme.helpdesk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper=true)
public class AssetCreateReq extends AssetFieldsReq {
    /** 旧版新增表单传来的编号按 ERP 编号接收。 */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Size(max=32) private String no;
    public AssetCreateReq() { setStatus("待入库"); }
}
