package com.tzme.helpdesk.controller;

import com.tzme.helpdesk.common.ApiResponse;
import com.tzme.helpdesk.domain.AssetRequest;
import com.tzme.helpdesk.dto.ApproveReq;
import com.tzme.helpdesk.dto.AssetRequestCreateReq;
import com.tzme.helpdesk.dto.RejectReq;
import com.tzme.helpdesk.service.AssetRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/asset-requests")
@RequiredArgsConstructor
public class AssetRequestController {

    private final AssetRequestService service;

    @GetMapping
    @com.tzme.helpdesk.security.RequiresPermission({"request.view.own","request.view.all"})
    public ApiResponse<List<AssetRequest>> list(@RequestParam(required = false) String status,
                                               @RequestParam(required = false) Boolean mine,
                                               @RequestParam(required = false) String type,
                                               @RequestParam(required = false) String no) {
        return ApiResponse.ok(service.list(status, mine, type, no));
    }

    @PostMapping
    @com.tzme.helpdesk.security.RequiresPermission({"request.create"})
    public ApiResponse<AssetRequest> create(@Valid @RequestBody AssetRequestCreateReq req) {
        return ApiResponse.ok(service.create(req));
    }

    @PostMapping("/{id}/approve")
    @com.tzme.helpdesk.security.RequiresPermission({"request.approve"})
    public ApiResponse<AssetRequest> approve(@PathVariable Long id,
                                             @Valid @RequestBody(required = false) ApproveReq req) {
        return ApiResponse.ok(service.approve(id, req));
    }

    @PostMapping("/{id}/reject")
    @com.tzme.helpdesk.security.RequiresPermission({"request.approve"})
    public ApiResponse<AssetRequest> reject(@PathVariable Long id, @Valid @RequestBody RejectReq req) {
        return ApiResponse.ok(service.reject(id, req.getReason()));
    }

    /** 线下交接确认：待领取 → 交付设备；待归还 → 收回设备（更换会先收回旧机再转待领取） */
    @PostMapping("/{id}/handover")
    @com.tzme.helpdesk.security.RequiresPermission({"request.approve"})
    public ApiResponse<AssetRequest> handover(@PathVariable Long id) {
        return ApiResponse.ok(service.confirmHandover(id));
    }

    @PostMapping("/{id}/cancel")
    @com.tzme.helpdesk.security.RequiresPermission({"request.create"})
    public ApiResponse<AssetRequest> cancel(@PathVariable Long id) {
        return ApiResponse.ok(service.cancel(id));
    }
}
