package com.tzme.helpdesk.controller;

import com.tzme.helpdesk.common.ApiResponse;
import com.tzme.helpdesk.common.BizException;
import com.tzme.helpdesk.common.CurrentUser;
import com.tzme.helpdesk.security.LoginUser;
import com.tzme.helpdesk.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    @GetMapping("/overview")
    @com.tzme.helpdesk.security.RequiresPermission({"stats.view"})
    public ApiResponse<Map<String, Object>> overview() {
        requireIt();
        return ApiResponse.ok(statsService.overview());
    }

    @GetMapping("/trend")
    @com.tzme.helpdesk.security.RequiresPermission({"stats.view"})
    public ApiResponse<List<Map<String, Object>>> trend(@RequestParam(defaultValue = "14") int days) {
        requireIt();
        return ApiResponse.ok(statsService.trend(days));
    }

    @GetMapping("/category")
    @com.tzme.helpdesk.security.RequiresPermission({"stats.view"})
    public ApiResponse<List<Map<String, Object>>> category(@RequestParam(defaultValue = "30") int days) {
        requireIt();
        return ApiResponse.ok(statsService.category(days));
    }

    @GetMapping("/engineers")
    @com.tzme.helpdesk.security.RequiresPermission({"stats.view"})
    public ApiResponse<List<Map<String, Object>>> engineers(@RequestParam(defaultValue = "30") int days) {
        requireIt();
        return ApiResponse.ok(statsService.engineers(days));
    }

    @GetMapping("/problem-assets")
    @com.tzme.helpdesk.security.RequiresPermission({"stats.view"})
    public ApiResponse<List<Map<String, Object>>> problemAssets(@RequestParam(defaultValue = "5") int limit) {
        requireIt();
        return ApiResponse.ok(statsService.problemAssets(limit));
    }

    private void requireIt() {
        LoginUser u = CurrentUser.require();
        if (!(u.hasPermission("stats.view"))) {
            throw new BizException(403, "统计看板仅对 IT 人员开放");
        }
    }
}
