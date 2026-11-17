package com.tzme.helpdesk.controller;

import com.tzme.helpdesk.common.ApiResponse;
import com.tzme.helpdesk.common.CurrentUser;
import com.tzme.helpdesk.domain.SolutionCode;
import com.tzme.helpdesk.domain.User;
import com.tzme.helpdesk.mapper.SolutionCodeMapper;
import com.tzme.helpdesk.mapper.UserMapper;
import com.tzme.helpdesk.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/meta")
@RequiredArgsConstructor
public class MetaController {

    private final SolutionCodeMapper solutionCodeMapper;
    private final UserMapper userMapper;
    private final com.tzme.helpdesk.service.RuntimeSettingsService settings;
    private final com.tzme.helpdesk.service.RolePermissionService rolePermissions;

    /** 问题分类字典：cat1 -> cat2 列表 */
    @GetMapping("/categories")
    public ApiResponse<Map<String, List<String>>> categories() {
        return ApiResponse.ok(settings.get().getCategories());
    }

    @GetMapping("/categories-by-type")
    public ApiResponse<Map<String, Map<String, List<String>>>> categoriesByType() {
        return ApiResponse.ok(settings.get().getCategoriesByType());
    }

    @GetMapping("/runtime")
    public ApiResponse<Map<String,Object>> runtime() {
        var s = settings.get();
        return ApiResponse.ok(Map.of("maxAttachments",s.getMaxAttachments(),"maxUploadMb",s.getMaxUploadMb(),
                "kbRecommendEnabled",s.getKbRecommendEnabled(),"requireSolutionCode",s.getRequireSolutionCode()));
    }

    @GetMapping("/solution-codes")
    public ApiResponse<List<SolutionCode>> solutionCodes() {
        return ApiResponse.ok(solutionCodeMapper.listAll());
    }

    @GetMapping("/engineers")
    @com.tzme.helpdesk.security.RequiresPermission({"ticket.dispatch","user.manage"})
    public ApiResponse<List<User>> engineers() {
        return ApiResponse.ok(userMapper.listActive().stream().filter(user -> rolePermissions.resolve(user).hasPermission("ticket.handle")).toList());
    }

    @GetMapping("/me")
    public ApiResponse<LoginUser> me() {
        return ApiResponse.ok(CurrentUser.require());
    }
}
