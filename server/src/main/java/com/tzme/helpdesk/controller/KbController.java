package com.tzme.helpdesk.controller;

import com.tzme.helpdesk.common.ApiResponse;
import com.tzme.helpdesk.domain.KbArticle;
import com.tzme.helpdesk.service.KbService;
import com.tzme.helpdesk.service.KnowledgeManagementService;
import com.tzme.helpdesk.service.TicketService;
import com.tzme.helpdesk.dto.*;
import com.tzme.helpdesk.common.PageResult;
import com.tzme.helpdesk.security.RequiresPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/kb")
@RequiredArgsConstructor
public class KbController {

    private final KbService kbService;
    private final KnowledgeManagementService management;
    private final TicketService tickets;

    @GetMapping("/manage")
    @RequiresPermission({"kb.manage"})
    public ApiResponse<PageResult<KbArticle>> managedList(@RequestParam(required=false) String keyword,
            @RequestParam(required=false) String status, @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok(management.list(keyword, status, page, size));
    }

    @GetMapping("/manage/{id}")
    @RequiresPermission({"kb.manage"})
    public ApiResponse<KbArticle> managedDetail(@PathVariable Long id) { return ApiResponse.ok(management.detail(id)); }

    @PostMapping("/manage/from-ticket")
    @RequiresPermission({"kb.manage"})
    public ApiResponse<KbArticle> capture(@Valid @RequestBody KbCaptureReq input) {
        return ApiResponse.ok(tickets.captureKnowledge(input.getTicketNo().trim()));
    }

    @PutMapping("/manage/{id}")
    @RequiresPermission({"kb.manage"})
    public ApiResponse<KbArticle> save(@PathVariable Long id, @Valid @RequestBody KbSaveReq input) {
        return ApiResponse.ok(management.save(id, input));
    }

    @PostMapping("/manage/{id}/{action:publish|withdraw|archive}")
    @RequiresPermission({"kb.manage"})
    public ApiResponse<KbArticle> changeStatus(@PathVariable Long id, @PathVariable String action,
            @Valid @RequestBody KbRevisionReq input) {
        return ApiResponse.ok(management.changeStatus(id, input.getRevision(), action));
    }

    @GetMapping
    @com.tzme.helpdesk.security.RequiresPermission({"kb.view"})
    public ApiResponse<List<KbArticle>> list(@RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String category) {
        return ApiResponse.ok(kbService.list(keyword, category));
    }

    @GetMapping("/recommend")
    @com.tzme.helpdesk.security.RequiresPermission({"kb.view"})
    public ApiResponse<List<KbArticle>> recommend(@RequestParam(required = false) String cat1,
                                                  @RequestParam(required = false) String desc) {
        return ApiResponse.ok(kbService.recommend(cat1, desc));
    }

    @GetMapping("/{id}")
    @com.tzme.helpdesk.security.RequiresPermission({"kb.view"})
    public ApiResponse<KbArticle> detail(@PathVariable Long id,
                                        @RequestHeader(name = "X-Tutorial-Practice", defaultValue = "0") String practice) {
        return ApiResponse.ok(kbService.detail(id, !"1".equals(practice)));
    }

    @PostMapping("/{id}/vote")
    @com.tzme.helpdesk.security.RequiresPermission({"kb.vote"})
    public ApiResponse<Void> vote(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        kbService.vote(id, Boolean.TRUE.equals(body.get("helpful")));
        return ApiResponse.ok();
    }
}
