package com.tzme.helpdesk.controller;
import com.tzme.helpdesk.common.ApiResponse;
import com.tzme.helpdesk.domain.AssetCategory;
import com.tzme.helpdesk.dto.AssetCategoryReq;
import com.tzme.helpdesk.security.RequiresPermission;
import com.tzme.helpdesk.service.AssetCategoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequestMapping("/asset-categories")
@RequiredArgsConstructor
public class AssetCategoryController {
    private final AssetCategoryService service;
    @GetMapping @RequiresPermission("asset.view") public ApiResponse<List<AssetCategory>> list() { return ApiResponse.ok(service.list()); }
    @PostMapping @RequiresPermission("asset.create") public ApiResponse<AssetCategory> create(@Valid @RequestBody AssetCategoryReq req) { return ApiResponse.ok(service.save(null,req)); }
    @PutMapping("/{id}") @RequiresPermission("asset.create") public ApiResponse<AssetCategory> update(@PathVariable Long id,@Valid @RequestBody AssetCategoryReq req) { return ApiResponse.ok(service.save(id,req)); }
    @DeleteMapping("/{id}") @RequiresPermission("asset.create") public ApiResponse<Void> delete(@PathVariable Long id) { service.delete(id); return ApiResponse.ok(); }
}
