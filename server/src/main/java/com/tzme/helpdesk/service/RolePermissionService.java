package com.tzme.helpdesk.service;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tzme.helpdesk.common.*;
import com.tzme.helpdesk.domain.User;
import com.tzme.helpdesk.dto.RolePermissionsReq;
import com.tzme.helpdesk.mapper.*;
import com.tzme.helpdesk.security.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@DependsOn("managementSchemaInitializer")
@RequiredArgsConstructor
public class RolePermissionService {
    private final ManagementMapper mapper;
    private final UserMapper users;
    private final ObjectMapper json;
    private final AuditService audit;
    public RolePermissionsReq config() {
        String value=mapper.setting("role-permissions");
        if(value!=null) return inheritSelfService(read(value));
        RolePermissionsReq result=new RolePermissionsReq();
        Roles.CONFIGURABLE.forEach(role -> result.getAssignments().put(role,Permissions.defaults(role)));
        result.setSelfServiceVersion(1);
        return result;
    }
    public LoginUser resolve(User user) {
        String role=Roles.normalize(user.getRole());
        return new LoginUser(user.getId(),user.getName(),role,user.getDeptName(),permissionsFor(role));
    }
    public List<String> permissionsFor(String role) {
        if (Roles.SUPER_ADMIN.equals(role)) return Permissions.defaults(role);
        return config().getAssignments().getOrDefault(role, List.of());
    }
    public Map<String,Object> describe() {
        RolePermissionsReq config=config();
        Map<String,List<String>> defaults=new LinkedHashMap<>(); Roles.CONFIGURABLE.forEach(role -> defaults.put(role,Permissions.defaults(role)));
        return Map.of("roles",Roles.CONFIGURABLE,"roleNames",Roles.LABELS,"capabilities",Permissions.CATALOG,
                "revision",config.getRevision(),"assignments",config.getAssignments(),"defaults",defaults,"selfServiceVersion",config.getSelfServiceVersion(),"selfServicePermissions",Permissions.SELF_SERVICE);
    }
    @Transactional public Map<String,Object> save(RolePermissionsReq input) {
        Permissions.require("role.manage");
        mapper.lockSetting("management-lock");
        User actor=users.findById(CurrentUser.require().id());
        if(actor==null || !"ACTIVE".equals(actor.getStatus()) || !Roles.isAdmin(actor.getRole())) throw new BizException(403,"仅管理员可以配置角色权限");
        inheritSelfService(input);
        validate(input);
        mapper.createSetting("role-permissions",write(config()));
        RolePermissionsReq previous=inheritSelfService(read(mapper.lockSetting("role-permissions")));
        if(!previous.getRevision().equals(input.getRevision())) throw new BizException(409,"权限已被其他管理员修改，请重新加载后再保存");
        input.setRevision(previous.getRevision()+1);
        mapper.updateSetting("role-permissions",write(input));
        List<String> changes=new ArrayList<>();
        for(String role:Roles.CONFIGURABLE) {
            var before=previous.getAssignments().get(role); var after=input.getAssignments().get(role);
            var added=Permissions.CATALOG.stream().filter(cap -> after.contains(cap.key()) && !before.contains(cap.key())).map(Permissions.Capability::name).toList();
            var removed=Permissions.CATALOG.stream().filter(cap -> before.contains(cap.key()) && !after.contains(cap.key())).map(Permissions.Capability::name).toList();
            if(!added.isEmpty() || !removed.isEmpty()) changes.add(Roles.LABELS.get(role)+"：增加="+added+"，取消="+removed);
        }
        audit.record("ROLE_PERMISSIONS_UPDATE","roles","权限版本 "+previous.getRevision()+" → "+input.getRevision()+"；"+String.join("；",changes));
        return describe();
    }
    public void validate(RolePermissionsReq input) {
        if(!input.getAssignments().keySet().equals(new HashSet<>(Roles.CONFIGURABLE))) throw new BizException("必须配置普通用户、IT、管理员、可查看用户四种角色");
        Map<String,Permissions.Capability> known=new HashMap<>(); Permissions.CATALOG.forEach(cap -> known.put(cap.key(),cap));
        input.getAssignments().forEach((role,keys) -> {
            if(keys==null || keys.size()!=new HashSet<>(keys).size() || keys.stream().anyMatch(key -> !known.containsKey(key))) throw new BizException("权限标识不合法或重复");
            for(String key:keys) {
                if(!keys.containsAll(known.get(key).requires())) throw new BizException(Roles.LABELS.get(role)+"的权限缺少依赖："+known.get(key).name());
                if("VIEWER".equals(role) && !known.get(key).readOnly() && !Permissions.SELF_SERVICE.contains(key)) throw new BizException("可查看用户只能办理本人业务，管理功能仅可查看");
                if("role.manage".equals(key) && !"ADMIN".equals(role)) throw new BizException("角色权限配置仅允许管理员使用");
            }
        });
        if(!input.getAssignments().get("ADMIN").contains("role.manage")) throw new BizException("必须保留管理员的角色权限配置权限");
    }
    private RolePermissionsReq inheritSelfService(RolePermissionsReq config) {
        if (Integer.valueOf(1).equals(config.getSelfServiceVersion())) return config;
        config.getAssignments().replaceAll((role, existing) -> {
            if (existing == null) return null;
            var permissions = new ArrayList<>(existing);
            Permissions.SELF_SERVICE.forEach(key -> { if (!permissions.contains(key)) permissions.add(key); });
            return permissions;
        });
        config.setSelfServiceVersion(1);
        return config;
    }
    private RolePermissionsReq read(String value) {
        try { return json.readValue(value,RolePermissionsReq.class); } catch(JsonProcessingException e) { throw new IllegalStateException("读取角色权限失败",e); }
    }
    private String write(RolePermissionsReq value) {
        try { return json.writeValueAsString(value); } catch(JsonProcessingException e) { throw new IllegalStateException("保存角色权限失败",e); }
    }
}
