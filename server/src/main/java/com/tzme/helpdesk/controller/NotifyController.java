package com.tzme.helpdesk.controller;

import com.tzme.helpdesk.common.ApiResponse;
import com.tzme.helpdesk.common.CurrentUser;
import com.tzme.helpdesk.domain.Notify;
import com.tzme.helpdesk.service.NotifyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/notifies")
@RequiredArgsConstructor
public class NotifyController {

    private final NotifyService notifyService;

    @GetMapping
    public ApiResponse<List<Notify>> list() {
        return ApiResponse.ok(notifyService.list(CurrentUser.require()));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Long>> unread() {
        return ApiResponse.ok(Map.of("count", notifyService.unread(CurrentUser.require())));
    }

    @PostMapping("/{id}/read")
    public ApiResponse<Void> read(@PathVariable Long id) {
        notifyService.markRead(id, CurrentUser.require());
        return ApiResponse.ok();
    }

    @PostMapping("/read-all")
    public ApiResponse<Void> readAll() {
        notifyService.markAllRead(CurrentUser.require());
        return ApiResponse.ok();
    }
}
