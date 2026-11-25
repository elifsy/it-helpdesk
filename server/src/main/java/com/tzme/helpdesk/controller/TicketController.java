package com.tzme.helpdesk.controller;

import com.tzme.helpdesk.common.ApiResponse;
import com.tzme.helpdesk.common.PageResult;
import com.tzme.helpdesk.domain.Ticket;
import com.tzme.helpdesk.dto.*;
import com.tzme.helpdesk.service.TicketService;
import com.tzme.helpdesk.vo.TicketDetailVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;

    @GetMapping
    @com.tzme.helpdesk.security.RequiresPermission({"ticket.view.own","ticket.view.all"})
    public ApiResponse<PageResult<Ticket>> list(@RequestParam(required = false) String status,
                                                @RequestParam(required = false) Boolean open,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) Boolean mine,
                                                @RequestParam(required = false) String priority,
                                                @RequestParam(required = false) String cat1,
                                                @RequestParam(required = false) String assetNo,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(ticketService.list(status, open, keyword, mine, priority, cat1, assetNo, page, size));
    }

    @PostMapping
    @com.tzme.helpdesk.security.RequiresPermission({"ticket.create"})
    public ApiResponse<Ticket> create(@Valid @RequestBody TicketCreateReq req) {
        return ApiResponse.ok(ticketService.create(req));
    }

    @GetMapping("/{no}")
    @com.tzme.helpdesk.security.RequiresPermission({"ticket.view.own","ticket.view.all"})
    public ApiResponse<TicketDetailVO> detail(@PathVariable String no) {
        return ApiResponse.ok(ticketService.detail(no));
    }

    @PostMapping("/{no}/accept")
    @com.tzme.helpdesk.security.RequiresPermission({"ticket.accept"})
    public ApiResponse<Ticket> accept(@PathVariable String no,
                                      @RequestBody(required = false) RemarkReq req) {
        return ApiResponse.ok(ticketService.accept(no, req));
    }

    @PostMapping("/{no}/dispatch")
    @com.tzme.helpdesk.security.RequiresPermission({"ticket.dispatch"})
    public ApiResponse<Ticket> dispatch(@PathVariable String no, @Valid @RequestBody DispatchReq req) {
        return ApiResponse.ok(ticketService.dispatch(no, req));
    }

    @PostMapping("/{no}/records")
    @com.tzme.helpdesk.security.RequiresPermission({"ticket.handle"})
    public ApiResponse<Ticket> addRecord(@PathVariable String no, @Valid @RequestBody RecordReq req) {
        return ApiResponse.ok(ticketService.addRecord(no, req));
    }

    @PostMapping("/{no}/finish")
    @com.tzme.helpdesk.security.RequiresPermission({"ticket.handle"})
    public ApiResponse<Ticket> finish(@PathVariable String no, @Valid @RequestBody FinishReq req) {
        return ApiResponse.ok(ticketService.finish(no, req));
    }

    @PostMapping("/{no}/confirm")
    @com.tzme.helpdesk.security.RequiresPermission({"ticket.confirm"})
    public ApiResponse<Ticket> confirm(@PathVariable String no, @RequestBody RateReq req) {
        return ApiResponse.ok(ticketService.confirm(no, req == null ? new RateReq() : req));
    }

    @PostMapping("/{no}/request-info")
    @com.tzme.helpdesk.security.RequiresPermission("ticket.handle")
    public ApiResponse<Ticket> requestInfo(@PathVariable String no, @Valid @RequestBody TicketInfoReq req) {
        return ApiResponse.ok(ticketService.requestInfo(no, req));
    }

    @PostMapping("/{no}/provide-info")
    @com.tzme.helpdesk.security.RequiresPermission({"ticket.confirm","ticket.handle"})
    public ApiResponse<Ticket> provideInfo(@PathVariable String no, @Valid @RequestBody TicketInfoReq req) {
        return ApiResponse.ok(ticketService.provideInfo(no, req));
    }

    @PostMapping("/{no}/rate")
    @com.tzme.helpdesk.security.RequiresPermission({"ticket.confirm"})
    public ApiResponse<Ticket> rate(@PathVariable String no, @Valid @RequestBody RateReq req) {
        return ApiResponse.ok(ticketService.rate(no, req));
    }

    @PostMapping("/{no}/reject")
    @com.tzme.helpdesk.security.RequiresPermission({"ticket.accept"})
    public ApiResponse<Ticket> reject(@PathVariable String no, @Valid @RequestBody RejectReq req) {
        return ApiResponse.ok(ticketService.reject(no, req));
    }

    @PostMapping("/{no}/cancel")
    @com.tzme.helpdesk.security.RequiresPermission({"ticket.confirm"})
    public ApiResponse<Ticket> cancel(@PathVariable String no) {
        return ApiResponse.ok(ticketService.cancel(no));
    }
}
