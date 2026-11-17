package com.tzme.helpdesk.controller;

import com.tzme.helpdesk.common.ApiResponse;
import com.tzme.helpdesk.dto.LoginReq;
import com.tzme.helpdesk.config.HelpdeskProperties;
import com.tzme.helpdesk.service.AuthService;
import com.tzme.helpdesk.vo.LoginVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final HelpdeskProperties props;

    @GetMapping("/config")
    public ApiResponse<java.util.Map<String, Object>> config() {
        return ApiResponse.ok(java.util.Map.of("demoEnabled", !props.getDingtalk().isEnabled(),
                "corpId", props.getDingtalk().getCorpId()));
    }

    @PostMapping("/login")
    public ApiResponse<LoginVO> login(@RequestBody(required = false) LoginReq req) {
        return ApiResponse.ok(authService.login(req == null ? new LoginReq() : req));
    }

    @GetMapping("/me")
    public ApiResponse<LoginVO> me() {
        return ApiResponse.ok(authService.me());
    }
}
