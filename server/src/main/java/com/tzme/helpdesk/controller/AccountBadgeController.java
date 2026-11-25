package com.tzme.helpdesk.controller;

import com.tzme.helpdesk.common.ApiResponse;
import com.tzme.helpdesk.service.AccountBadgeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/account/badge")
public class AccountBadgeController {
    private final AccountBadgeService service;

    public record AvatarRequest(@NotNull AccountBadgeService.AvatarSource source) { }
    public record TitleSettings(@NotNull Boolean enabled) { }

    @GetMapping
    public ApiResponse<AccountBadgeService.Badge> badge() {
        return ApiResponse.ok(service.badge());
    }

    @PutMapping("/avatar")
    public ApiResponse<AccountBadgeService.Badge> avatar(@Valid @RequestBody AvatarRequest request) {
        return ApiResponse.ok(service.saveAvatar(request.source()));
    }

    @GetMapping("/titles")
    public ApiResponse<TitleSettings> titleSettings() {
        return ApiResponse.ok(new TitleSettings(service.titleSettings()));
    }

    @PutMapping("/titles")
    public ApiResponse<TitleSettings> setTitles(@Valid @RequestBody TitleSettings request) {
        return ApiResponse.ok(new TitleSettings(service.setTitlesEnabled(request.enabled())));
    }
}
