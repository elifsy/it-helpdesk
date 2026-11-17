package com.tzme.helpdesk.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper=true)
public class AssetUpdateReq extends AssetFieldsReq {
    @NotBlank @Pattern(regexp="[a-f0-9]{64}") private String editToken;
}
