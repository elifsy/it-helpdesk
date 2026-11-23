package com.tzme.helpdesk.controller;

import com.tzme.helpdesk.common.ApiResponse;
import com.tzme.helpdesk.common.PageResult;
import com.tzme.helpdesk.common.CurrentUser;
import com.tzme.helpdesk.domain.Asset;
import com.tzme.helpdesk.service.AssetService;
import com.tzme.helpdesk.service.AssetWorkbook;
import com.tzme.helpdesk.service.MissingReportGuard;
import com.tzme.helpdesk.security.Roles;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/assets")
@RequiredArgsConstructor
public class AssetController {

    private final AssetService assetService;
    private final AssetWorkbook workbook;
    private final MissingReportGuard missingReports;

    private void checkPersonalAssetAccess() {
        var user = CurrentUser.require();
        if (!Roles.isItOrAdmin(user.role())) missingReports.requireCanBrowsePersonalAssets(user.id());
    }

    @GetMapping(params = "!page")
    @com.tzme.helpdesk.security.RequiresPermission({"asset.view"})
    public ApiResponse<List<Asset>> list(@RequestParam(required = false) String status,
                                         @RequestParam(required = false) String category,
                                         @RequestParam(required = false) String keyword) {
        checkPersonalAssetAccess();
        return ApiResponse.ok(assetService.list(status, category, keyword));
    }

    @GetMapping(params = "page")
    @com.tzme.helpdesk.security.RequiresPermission("asset.view")
    public ApiResponse<PageResult<Asset>> page(@RequestParam(required = false) String status,
                                               @RequestParam(required = false) String category,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam int page,
                                               @RequestParam(defaultValue = "15") int size) {
        checkPersonalAssetAccess();
        return ApiResponse.ok(assetService.page(status, category, keyword, page, size));
    }

    @GetMapping("/{no}")
    @com.tzme.helpdesk.security.RequiresPermission({"asset.view"})
    public ApiResponse<Map<String, Object>> detail(@PathVariable String no) {
        checkPersonalAssetAccess();
        return ApiResponse.ok(assetService.detail(no));
    }

    @GetMapping("/{no}/qrcode")
    @com.tzme.helpdesk.security.RequiresPermission({"asset.view"})
    public ApiResponse<Map<String, String>> qrcode(@PathVariable String no) {
        checkPersonalAssetAccess();
        return ApiResponse.ok(Map.of("assetNo", no, "content", assetService.qrcode(no)));
    }

    @PostMapping
    @com.tzme.helpdesk.security.RequiresPermission({"asset.create"})
    public ApiResponse<Asset> create(@jakarta.validation.Valid @RequestBody com.tzme.helpdesk.dto.AssetCreateReq input) {
        return ApiResponse.ok(assetService.create(input));
    }

    @GetMapping("/import/template")
    @com.tzme.helpdesk.security.RequiresPermission("asset.create")
    public ResponseEntity<byte[]> importTemplate() {
        String filename = ContentDisposition.attachment()
                .filename("资产批量导入模板.xlsx", StandardCharsets.UTF_8).build().toString();
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, filename)
                .contentType(MediaType.parseMediaType(AssetWorkbook.CONTENT_TYPE)).body(workbook.template());
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @com.tzme.helpdesk.security.RequiresPermission("asset.create")
    public ApiResponse<Map<String, Integer>> importAssets(@RequestParam(value = "file", required = false) MultipartFile file) {
        int imported = assetService.importBatch(workbook.read(file));
        return ApiResponse.ok(Map.of("imported", imported));
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
