package com.tzme.helpdesk.security;

import java.util.*;

public final class Roles {
    public static final String SUPER_ADMIN = "SUPER_ADMIN";
    public static final List<String> ALL = List.of("USER", "IT", "ADMIN", "VIEWER", SUPER_ADMIN);
    public static final List<String> CONFIGURABLE = List.of("USER", "IT", "ADMIN", "VIEWER");
    public static final Map<String,String> LABELS = Map.of("USER","普通用户","IT","IT","ADMIN","管理员","VIEWER","可查看用户",
            SUPER_ADMIN,"超级管理员");
    private Roles() { }
    /** 旧角色仅在升级时合并，每个账号最终只保留一个角色。 */
    public static String normalize(String value) {
        List<String> old = value == null ? List.of() : Arrays.stream(value.split(",")).map(String::trim).toList();
        if (old.contains(SUPER_ADMIN)) return SUPER_ADMIN;
        if (old.contains("ADMIN")) return "ADMIN";
        if (old.stream().anyMatch(r -> List.of("IT","DESK","ENGINEER","IT_MANAGER","ASSET_ADMIN").contains(r))) return "IT";
        if (old.contains("VIEWER")) return "VIEWER";
        return "USER";
    }

    public static boolean isItOrAdmin(String value) {
        String role = normalize(value);
        return "IT".equals(role) || isAdmin(role);
    }

    public static boolean isAdmin(String value) {
        String role = normalize(value);
        return "ADMIN".equals(role) || SUPER_ADMIN.equals(role);
    }
}
