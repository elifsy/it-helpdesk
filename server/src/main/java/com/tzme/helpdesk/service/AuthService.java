package com.tzme.helpdesk.service;

import com.tzme.helpdesk.common.BizException;
import com.tzme.helpdesk.common.CurrentUser;
import com.tzme.helpdesk.config.HelpdeskProperties;
import com.tzme.helpdesk.domain.User;
import com.tzme.helpdesk.dto.LoginReq;
import com.tzme.helpdesk.mapper.UserMapper;
import com.tzme.helpdesk.security.JwtUtil;
import com.tzme.helpdesk.security.LoginUser;
import com.tzme.helpdesk.vo.LoginVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final DingTalkService dingTalk;
    private final HelpdeskProperties props;
    private final RolePermissionService permissions;
    private final DingTalkAccountService accounts;

    public LoginVO login(LoginReq req) {
        User u;
        if (dingTalk.enabled()) {
            String dingUserId = dingTalk.getUserIdByCode(req.getAuthCode());
            u = userMapper.findByDingId(dingUserId);
            if (u == null) {
                u = accounts.resolve(dingUserId, dingTalk.getUserDetail(dingUserId));
            } else if (dingUserId.equals(props.getDingtalk().getBootstrapAdminUserId().trim())) {
                u = accounts.resolve(dingUserId, null);
            }
        } else {
            String name = req.getDemoUser() == null || req.getDemoUser().isBlank()
                    ? props.getDemoUser() : req.getDemoUser();
            u = userMapper.findByName(name);
            if (u == null) {
                throw new BizException(404, "演示账号不存在：" + name);
            }
        }
        if (!"ACTIVE".equals(u.getStatus())) {
            throw new BizException(403, "账号已停用");
        }
        LoginUser lu = permissions.resolve(u);
        String token = jwtUtil.create(lu);
        return new LoginVO(token, u.getId(), u.getName(), u.getDeptName(), lu.role(), lu.roles(), u.getDingUserId(), lu.permissions());
    }

    public LoginVO me() {
        User user = userMapper.findById(CurrentUser.require().id());
        if (user == null || !"ACTIVE".equals(user.getStatus())) throw new BizException(401, "账号已失效，请重新登录");
        LoginUser lu = permissions.resolve(user);
        return new LoginVO(null, user.getId(), user.getName(), user.getDeptName(), lu.role(), lu.roles(), user.getDingUserId(), lu.permissions());
    }
}
