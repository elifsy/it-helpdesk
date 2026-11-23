package com.tzme.helpdesk.controller;

import com.tzme.helpdesk.common.ApiResponse;
import com.tzme.helpdesk.dto.IncomingNotificationRules;
import com.tzme.helpdesk.security.RequiresPermission;
import com.tzme.helpdesk.service.IncomingNotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/manage/incoming-notifications")
@RequiredArgsConstructor
public class IncomingNotificationController {
    private final IncomingNotificationService service;

    @GetMapping
    @RequiresPermission("settings.manage")
    public ApiResponse<IncomingNotificationService.Config> config() {
        return ApiResponse.ok(service.config());
    }

    @PutMapping
    @RequiresPermission("settings.manage")
    public ApiResponse<IncomingNotificationRules> save(@Valid @RequestBody IncomingNotificationRules rules) {
        return ApiResponse.ok(service.save(rules));
    }
}
