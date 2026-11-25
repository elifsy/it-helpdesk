package com.tzme.helpdesk.controller;

import com.tzme.helpdesk.common.ApiResponse;
import com.tzme.helpdesk.common.BizException;
import com.tzme.helpdesk.common.CurrentUser;
import com.tzme.helpdesk.mapper.ManagementMapper;
import com.tzme.helpdesk.security.Roles;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/tutorials")
@RequiredArgsConstructor
public class TutorialController {
    private static final Set<String> MODULES = Set.of(
            "home", "repair", "my-tickets", "my-assets", "knowledge", "my-supplement",
            "workbench", "tickets", "ledger", "approvals", "supplement", "statistics", "knowledge-manage",
            "purchasing", "users", "departments", "permissions", "notifications", "contacts", "dingtalk",
            "settings", "sla", "dictionary", "audit", "storage", "jobs");
    private final ManagementMapper settings;

    public record Start(boolean shouldStart) { }

    @GetMapping
    public ApiResponse<List<String>> seen() {
        String prefix = prefix();
        return ApiResponse.ok(settings.settingKeysWithPrefix(prefix).stream()
                .map(key -> key.substring(prefix.length())).toList());
    }

    @PostMapping("/{module}/start")
    public ApiResponse<Start> start(@PathVariable String module) {
        String prefix = prefix();
        if (!MODULES.contains(module)) throw new BizException("找不到这项教程");
        return ApiResponse.ok(new Start(settings.createSetting(prefix + module, "true") == 1));
    }

    private static String prefix() {
        var user = CurrentUser.require();
        if (user.hasRole(Roles.SUPER_ADMIN)) throw new BizException(403, "当前角色无需新手教程");
        return "tutorial:" + user.id() + ":" + user.role() + ":";
    }
}
