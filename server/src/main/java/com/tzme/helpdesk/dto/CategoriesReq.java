package com.tzme.helpdesk.dto;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.*;
@Data
public class CategoriesReq {
    @NotNull @Min(0) private Long revision;
    private Map<String,List<String>> categories;
    private Map<String,Map<String,List<String>>> categoriesByType;
}
