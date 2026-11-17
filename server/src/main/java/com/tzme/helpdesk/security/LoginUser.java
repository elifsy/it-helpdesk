package com.tzme.helpdesk.security;

import java.util.Arrays;
import java.util.List;

public record LoginUser(Long id, String name, String role, String deptName, List<String> permissions) {
    public LoginUser {
        role=Roles.normalize(role);
        permissions=permissions==null ? List.of() : List.copyOf(permissions);
    }
    public LoginUser(Long id,String name,String role,String deptName) {
        this(id,name,role,deptName,Permissions.defaults(Roles.normalize(role)));
    }
    public List<String> roles() {
        return List.of(role);
    }

    public boolean hasRole(String target) {
        return roles().contains(target);
    }

    public boolean isEmployeeOnly() {
        return !hasPermission("request.view.all");
    }

    public boolean hasAnyRole(String... targets) {
        return Arrays.stream(targets).anyMatch(this::hasRole);
    }

    public boolean canManageTickets() {
        return hasPermission("ticket.view.all");
    }
    public boolean hasPermission(String key) { return permissions.contains(key); }
}
