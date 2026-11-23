package com.tzme.helpdesk.controller;

import com.tzme.helpdesk.common.ApiResponse;
import com.tzme.helpdesk.security.RequiresPermission;
import com.tzme.helpdesk.service.ServiceContactService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/meta/service-contacts")
public class ServiceContactController {
    private final ServiceContactService contacts;

    @GetMapping
    @RequiresPermission({"settings.manage","stats.view"})
    public ApiResponse<ServiceContactService.Roster> roster() {
        return ApiResponse.ok(contacts.roster());
    }

    @GetMapping("/display")
    public ApiResponse<ServiceContactService.Roster> display() {
        return ApiResponse.ok(contacts.displayRoster());
    }

    @GetMapping("/people")
    @RequiresPermission("settings.manage")
    public ApiResponse<List<ServiceContactService.Person>> search(@RequestParam String keyword) {
        return ApiResponse.ok(contacts.search(keyword));
    }
}
