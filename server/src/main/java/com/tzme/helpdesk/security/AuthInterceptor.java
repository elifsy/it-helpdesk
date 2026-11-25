package com.tzme.helpdesk.security;

import com.tzme.helpdesk.common.BizException;
import com.tzme.helpdesk.common.CurrentUser;
import com.tzme.helpdesk.config.HelpdeskProperties;
import com.tzme.helpdesk.domain.User;
import com.tzme.helpdesk.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;
    private final HelpdeskProperties props;
    private final com.tzme.helpdesk.service.RolePermissionService permissions;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        if ("1".equals(request.getHeader("X-Tutorial-Practice"))
                && !"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) {
            throw new BizException(409, "练习到此为止，操作没有提交。");
        }
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            LoginUser claims;
            try {
                claims = jwtUtil.parse(auth.substring(7));
            } catch (Exception e) {
                throw new BizException(401, "登录已过期，请重新进入应用");
            }
            User active = userMapper.findById(claims.id());
            if (active == null || !"ACTIVE".equals(active.getStatus())) {
                throw new BizException(403, "账号已停用，请联系管理员");
            }
            // 角色与在职状态以数据库为准，停用或撤权无需等待 JWT 过期。
            CurrentUser.set(permissions.resolve(active));
            return true;
        }
        // 本地演示：未开启钉钉免登时，允许通过 X-Demo-User 指定演示身份
        if (!props.getDingtalk().isEnabled()) {
            String demo = request.getHeader("X-Demo-User");
            if (demo == null || demo.isBlank()) {
                demo = props.getDemoUser();
            }
            User u = userMapper.findByName(demo);
            if (u == null) {
                throw new BizException(401, "演示账号不存在：" + demo);
            }
            CurrentUser.set(permissions.resolve(u));
            return true;
        }
        throw new BizException(401, "未登录");
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        CurrentUser.clear();
    }
}
