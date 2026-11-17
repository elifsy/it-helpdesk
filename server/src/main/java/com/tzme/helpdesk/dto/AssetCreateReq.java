package com.tzme.helpdesk.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper=true)
public class AssetCreateReq extends AssetFieldsReq {
    @NotBlank @Size(max=32) private String no;
    public AssetCreateReq() { setStatus("待入库"); }
}
