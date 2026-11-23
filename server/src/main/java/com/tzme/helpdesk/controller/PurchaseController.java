package com.tzme.helpdesk.controller;

import com.tzme.helpdesk.common.ApiResponse;
import com.tzme.helpdesk.domain.PurchaseCatalog;
import com.tzme.helpdesk.domain.PurchaseRequest;
import com.tzme.helpdesk.service.PurchaseService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 设备购入申请：采购目录维护 + 申请单流转。
 * 一张单可含多条明细（购物车），IT/管理员可针对每条明细单独「库存配发 / 转采购 / 确认交付」。
 */
@RestController
@RequestMapping
@RequiredArgsConstructor
public class PurchaseController {

    private final PurchaseService service;

    /* ------------------------------ 目录 ------------------------------ */

    @GetMapping("/purchase-catalog")
    @com.tzme.helpdesk.security.RequiresPermission({"request.create", "request.approve"})
    public ApiResponse<List<PurchaseCatalog>> catalog(@RequestParam(defaultValue = "false") boolean all) {
        return ApiResponse.ok(service.catalog(!all));
    }

    @PostMapping("/purchase-catalog")
    @com.tzme.helpdesk.security.RequiresPermission({"asset.create"})
    public ApiResponse<PurchaseCatalog> saveCatalog(@RequestBody PurchaseCatalog catalog) {
        return ApiResponse.ok(service.saveCatalog(catalog));
    }

    @DeleteMapping("/purchase-catalog/{id}")
    @com.tzme.helpdesk.security.RequiresPermission({"asset.create"})
    public ApiResponse<Void> deleteCatalog(@PathVariable Long id) {
        service.deleteCatalog(id);
        return ApiResponse.ok(null);
    }

    /* ------------------------------ 申请单 ------------------------------ */

    @Data
    public static class CartLineReq {
        private Long catalogId;
        private Integer qty;
    }

    @Data
    public static class CreateReq {
        private List<CartLineReq> items;
        private String reason;
    }

    @Data
    public static class ErpReq { private String erpNo; }

    @Data
    public static class AssetReq { private String assetNo; }

    @Data
    public static class RemarkReq { private String remark; }

    @GetMapping("/purchase-requests")
    @com.tzme.helpdesk.security.RequiresPermission({"request.view.own", "request.view.all"})
    public ApiResponse<List<PurchaseRequest>> list(@RequestParam(required = false) String status,
                                                    @RequestParam(required = false) Boolean mine) {
        return ApiResponse.ok(service.list(status, mine));
    }

    @GetMapping("/purchase-requests/{id}")
    @com.tzme.helpdesk.security.RequiresPermission({"request.view.own", "request.view.all"})
    public ApiResponse<PurchaseRequest> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping("/purchase-requests")
    @com.tzme.helpdesk.security.RequiresPermission({"request.create"})
    public ApiResponse<PurchaseRequest> create(@RequestBody CreateReq req) {
        List<PurchaseService.CartLine> lines = req.getItems() == null ? List.of()
                : req.getItems().stream()
                .map(i -> new PurchaseService.CartLine(i.getCatalogId(), i.getQty()))
                .toList();
        return ApiResponse.ok(service.create(lines, req.getReason()));
    }

    @PostMapping("/purchase-requests/{id}/approve")
    @com.tzme.helpdesk.security.RequiresPermission({"request.approve"})
    public ApiResponse<PurchaseRequest> approve(@PathVariable Long id, @RequestBody(required = false) RemarkReq req) {
        return ApiResponse.ok(service.approve(id, req == null ? null : req.getRemark()));
    }

    @PostMapping("/purchase-requests/{id}/reject")
    @com.tzme.helpdesk.security.RequiresPermission({"request.approve"})
    public ApiResponse<PurchaseRequest> reject(@PathVariable Long id, @RequestBody(required = false) RemarkReq req) {
        return ApiResponse.ok(service.reject(id, req == null ? null : req.getRemark()));
    }

    /** 单条明细：库存能满足，挑一台现成设备 → 待领取 */
    @PostMapping("/purchase-requests/{id}/items/{itemId}/assign")
    @com.tzme.helpdesk.security.RequiresPermission({"request.approve"})
    public ApiResponse<PurchaseRequest> assign(@PathVariable Long id, @PathVariable Long itemId,
                                                @RequestBody AssetReq req) {
        return ApiResponse.ok(service.assignStock(id, itemId, req.getAssetNo()));
    }

    /** 单条明细：库存不足，填 ERP 单号转采购 */
    @PostMapping("/purchase-requests/{id}/items/{itemId}/order")
    @com.tzme.helpdesk.security.RequiresPermission({"request.approve"})
    public ApiResponse<PurchaseRequest> order(@PathVariable Long id, @PathVariable Long itemId,
                                              @RequestBody ErpReq req) {
        return ApiResponse.ok(service.markOrdered(id, itemId, req.getErpNo()));
    }

    /** 单条明细：确认已交付给申请人 */
    @PostMapping("/purchase-requests/{id}/items/{itemId}/deliver")
    @com.tzme.helpdesk.security.RequiresPermission({"request.approve"})
    public ApiResponse<PurchaseRequest> deliver(@PathVariable Long id, @PathVariable Long itemId) {
        return ApiResponse.ok(service.deliver(id, itemId));
    }

    @PostMapping("/purchase-requests/{id}/cancel")
    @com.tzme.helpdesk.security.RequiresPermission({"request.create"})
    public ApiResponse<PurchaseRequest> cancel(@PathVariable Long id) {
        return ApiResponse.ok(service.cancel(id));
    }
}
