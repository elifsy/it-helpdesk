package com.tzme.helpdesk.controller;

import com.tzme.helpdesk.common.ApiResponse;
import com.tzme.helpdesk.common.BizException;
import com.tzme.helpdesk.common.CurrentUser;
import com.tzme.helpdesk.config.HelpdeskProperties;
import com.tzme.helpdesk.security.LoginUser;
import com.tzme.helpdesk.service.DingTalkContactService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminSyncController {

    private final DingTalkContactService contactService;
    private final HelpdeskProperties props;

    /**
     * 同步钉钉通讯录。
     * mode=dry 只统计不写；demo 用样例数据写库（本地无凭据时验证逻辑）；live 调真实钉钉。
     */
    @PostMapping("/sync/dingtalk")
    @com.tzme.helpdesk.security.RequiresPermission({"contact.sync"})
    public ApiResponse<Map<String, Object>> syncDingTalk(@RequestParam(defaultValue = "live") String mode) {
        LoginUser u = CurrentUser.require();
        if (!(u.hasPermission("contact.sync"))) {
            throw new BizException(403, "只有管理员可以同步通讯录");
        }
        if (!"dry".equalsIgnoreCase(mode) && !"demo".equalsIgnoreCase(mode)
                && !props.getDingtalk().isEnabled()) {
            throw new BizException("未开启钉钉接入（DINGTALK_ENABLED=false），无法同步真实通讯录；"
                    + "可先用 mode=demo 验证逻辑");
        }
        return ApiResponse.ok(contactService.sync(mode));
    }
}
