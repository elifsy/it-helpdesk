package com.tzme.helpdesk.domain;
import lombok.Data;
@Data
public class AssetCategory {
    private Long id;
    private String name;
    private Integer sort;
    private Long assetCount;
}
