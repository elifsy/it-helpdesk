package com.tzme.helpdesk.controller;

import com.tzme.helpdesk.common.ApiResponse;
import com.tzme.helpdesk.common.PageResult;
import com.tzme.helpdesk.common.BizException;
import com.tzme.helpdesk.common.CurrentUser;
import com.tzme.helpdesk.domain.Asset;
import com.tzme.helpdesk.mapper.AssetMapper;
import com.tzme.helpdesk.security.LoginUser;
import com.tzme.helpdesk.service.AssetService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/assets")
@RequiredArgsConstructor
public class AssetController {

    private final AssetService assetService;
    private final AssetMapper assetMapper;

    @GetMapping(params = "!page")
    @com.tzme.helpdesk.security.RequiresPermission({"asset.view"})
    public ApiResponse<List<Asset>> list(@RequestParam(required = false) String status,
                                         @RequestParam(required = false) String category,
                                         @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(assetService.list(status, category, keyword));
    }

    @GetMapping(params = "page")
    @com.tzme.helpdesk.security.RequiresPermission("asset.view")
    public ApiResponse<PageResult<Asset>> page(@RequestParam(required = false) String status,
                                               @RequestParam(required = false) String category,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam int page,
                                               @RequestParam(defaultValue = "15") int size) {
        return ApiResponse.ok(assetService.page(status, category, keyword, page, size));
    }

    @GetMapping("/{no}")
    @com.tzme.helpdesk.security.RequiresPermission({"asset.view"})
    public ApiResponse<Map<String, Object>> detail(@PathVariable String no) {
        return ApiResponse.ok(assetService.detail(no));
    }

    @GetMapping("/{no}/qrcode")
    @com.tzme.helpdesk.security.RequiresPermission({"asset.view"})
    public ApiResponse<Map<String, String>> qrcode(@PathVariable String no) {
        return ApiResponse.ok(Map.of("assetNo", no, "content", assetService.qrcode(no)));
    }

    @PostMapping
    @com.tzme.helpdesk.security.RequiresPermission({"asset.create"})
    public ApiResponse<Asset> create(@jakarta.validation.Valid @RequestBody com.tzme.helpdesk.dto.AssetCreateReq input) {
        return ApiResponse.ok(assetService.create(input));
    }

    @PutMapping("/{no}")
    @com.tzme.helpdesk.security.RequiresPermission("asset.create")
    public ApiResponse<Map<String,Object>> update(@PathVariable String no,@jakarta.validation.Valid @RequestBody com.tzme.helpdesk.dto.AssetUpdateReq req) {
        return ApiResponse.ok(assetService.updateDetails(no,req));
    }

    @PutMapping("/{no}/category")
    @com.tzme.helpdesk.security.RequiresPermission("asset.create")
    public ApiResponse<Asset> category(@PathVariable String no,@jakarta.validation.Valid @RequestBody com.tzme.helpdesk.dto.AssetCategoryAssignReq req) {
        return ApiResponse.ok(assetService.changeCategory(no,req.getCategory()));
    }
}
