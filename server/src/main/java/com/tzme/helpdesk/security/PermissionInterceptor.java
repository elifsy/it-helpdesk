package com.tzme.helpdesk.security;
import com.tzme.helpdesk.common.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import java.util.Arrays;
@Component
public class PermissionInterceptor implements HandlerInterceptor {
    @Override public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler) {
        if ("OPTIONS".equalsIgnoreCase(req.getMethod()) || !(handler instanceof HandlerMethod method)) return true;
        RequiresPermission rule=method.getMethodAnnotation(RequiresPermission.class);
        if (rule==null) rule=method.getBeanType().getAnnotation(RequiresPermission.class);
        if (rule!=null && Arrays.stream(rule.value()).noneMatch(CurrentUser.require()::hasPermission))
            throw new BizException(403,"当前角色没有该操作权限");
        return true;
    }
}
