package com.tzme.helpdesk.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.*;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Setter;

@Data
@EqualsAndHashCode(callSuper=true)
public class AssetUpdateReq extends AssetFieldsReq {
    @NotBlank @Pattern(regexp="[a-f0-9]{64}") private String editToken;
    @JsonIgnore @Setter(AccessLevel.NONE)
    private boolean erpNoProvided;

    @Override
    @JsonSetter("erpNo")
    public void setErpNo(String erpNo) {
        super.setErpNo(erpNo);
        erpNoProvided = true;
    }
}
