package com.tzme.helpdesk.common;

import com.tzme.helpdesk.security.LoginUser;

public final class CurrentUser {
    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private CurrentUser() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    public static LoginUser require() {
        LoginUser u = HOLDER.get();
        if (u == null) {
            throw new BizException(401, "未登录或登录已过期");
        }
        return u;
    }

    public static String name() {
        return require().name();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
