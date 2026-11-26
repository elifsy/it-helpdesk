package com.tzme.helpdesk.controller;

import com.tzme.helpdesk.common.ApiResponse;
import com.tzme.helpdesk.common.PageResult;
import com.tzme.helpdesk.domain.AssetRequest;
import com.tzme.helpdesk.domain.MissingReport;
import com.tzme.helpdesk.domain.Ticket;
import com.tzme.helpdesk.dto.TicketCreateReq;
import com.tzme.helpdesk.service.MissingReportService;
import com.tzme.helpdesk.service.MissingReportService.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/missing-reports")
public class MissingReportController {
    private final MissingReportService service;

    @GetMapping("/summary")
    public ApiResponse<Summary> summary() { return ApiResponse.ok(service.summary()); }

    @GetMapping("/mine")
    public ApiResponse<List<MissingReport>> mine() { return ApiResponse.ok(service.mine()); }

    @GetMapping
    public ApiResponse<PageResult<MissingReport>> list(@RequestParam(defaultValue = "1") int page) {
        return ApiResponse.ok(service.list(page));
    }

    @GetMapping("/requesters")
    public ApiResponse<List<Applicant>> requesters(@RequestParam String keyword) {
        return ApiResponse.ok(service.search(keyword));
    }

    @GetMapping("/asset-options")
    public ApiResponse<AssetOptions> assetOptions(@RequestParam Long requesterId, @RequestParam String type) {
        return ApiResponse.ok(service.assetOptions(requesterId, type));
    }

    @GetMapping("/{no}")
    public ApiResponse<Detail> detail(@PathVariable String no) { return ApiResponse.ok(service.detail(no)); }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<MissingReport> register(@Valid @RequestPart("report") Registration report,
                                               @RequestPart(value = "evidence", required = false) List<MultipartFile> evidence) {
        return ApiResponse.ok(service.register(report, evidence));
    }

    @PostMapping("/{no}/submit")
    public ApiResponse<Ticket> submit(@PathVariable String no, @Valid @RequestBody TicketCreateReq input) {
        return ApiResponse.ok(service.submit(no, input));
    }

    @PostMapping("/{no}/submit-asset")
    public ApiResponse<AssetRequest> submitAsset(@PathVariable String no, @Valid @RequestBody AssetSupplement input) {
        return ApiResponse.ok(service.submitAsset(no, input));
    }

    @PostMapping("/{no}/cancel")
    public ApiResponse<Void> cancel(@PathVariable String no, @Valid @RequestBody Cancellation input) {
        service.cancel(no, input);
        return ApiResponse.ok();
    }
}
