package com.tzme.helpdesk.service;

import com.tzme.helpdesk.common.*;
import com.tzme.helpdesk.domain.*;
import com.tzme.helpdesk.dto.*;
import com.tzme.helpdesk.mapper.*;
import com.tzme.helpdesk.security.LoginUser;
import com.tzme.helpdesk.security.Roles;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AdminManagementService {
    public static final List<String> ROLES = Roles.ALL;
    private final UserMapper users;
    private final DeptMapper depts;
    private final TicketMapper tickets;
    private final SolutionCodeMapper solutions;
    private final ManagementMapper management;
    private final AuditService audit;
    private final RolePermissionService rolePermissions;

    public static LoginUser requireAdmin() {
        LoginUser u = CurrentUser.require();
        if (!Roles.isAdmin(u.role())) throw new BizException(403,"只有系统管理员可以管理系统");
        return u;
    }
    private User lockManagement(String permission) {
        com.tzme.helpdesk.security.Permissions.require(permission);
        LoginUser current = CurrentUser.require();
        management.lockSetting("management-lock");
        User actor = users.findById(current.id());
        if (actor == null || !"ACTIVE".equals(actor.getStatus()) || !rolePermissions.resolve(actor).hasPermission(permission))
            throw new BizException(403,"管理权限已变更，请刷新页面");
        return actor;
    }
    @Transactional(readOnly = true)
    public PageResult<User> users(String keyword, String status, int page, int size) {
        com.tzme.helpdesk.security.Permissions.require("user.manage");
        int p = Math.max(1, page), s = Math.max(1, Math.min(100, size));
        long offset = (p - 1L) * s;
        var query = PersonSearch.query(keyword);
        if (query.value().isEmpty()) {
            return new PageResult<>(users.adminList(status, offset, s), users.adminCount(status), p, s);
        }
        List<Long> matched = users.listSearchablePeople(status, false).stream()
                .filter(query::matches).map(User::getId).toList();
        List<Long> pageIds = matched.stream().skip(offset).limit(s).toList();
        return new PageResult<>(pageIds.isEmpty() ? List.of() : users.adminListByIds(pageIds), matched.size(), p, s);
    }
    @Transactional
    public User updateUser(Long id, UserAdminReq req) {
        User actor = lockManagement("user.manage");
        if (!ROLES.contains(req.getRole())) throw new BizException("请选择有效角色");
        User target = users.findByIdForUpdate(id);
        if (target==null) throw new BizException(404,"用户不存在");
        String actorRole = Roles.normalize(actor.getRole());
        String targetRole = Roles.normalize(target.getRole());
        if (!Roles.SUPER_ADMIN.equals(actorRole) && (Roles.SUPER_ADMIN.equals(targetRole) || Roles.SUPER_ADMIN.equals(req.getRole())))
            throw new BizException(403,"仅超级管理员可以维护超级管理员账号");
        if (!Roles.isAdmin(actorRole) && (Roles.isAdmin(targetRole) || Roles.isAdmin(req.getRole())))
            throw new BizException(403,"仅管理员可以维护管理员账号");
        if (id.equals(actor.getId()) && Roles.isAdmin(actorRole) && (!actorRole.equals(req.getRole()) || !"ACTIVE".equals(req.getStatus())))
            throw new BizException("不能停用当前账号或移除自己的管理员角色");
        Dept dept = req.getDeptId()==0 ? null : depts.findById(req.getDeptId());
        if (req.getDeptId()!=0 && dept==null) throw new BizException("部门不存在");
        int moved=0;
        boolean losesHandling = !rolePermissions.permissionsFor(req.getRole()).contains("ticket.handle");
        if (("DISABLED".equals(req.getStatus()) || losesHandling) && tickets.countOpenAssigned(id)>0) {
            User next = req.getHandoverId()==null ? null : users.findByIdForUpdate(req.getHandoverId());
            if (next==null || id.equals(next.getId()) || !"ACTIVE".equals(next.getStatus())
                    || !rolePermissions.resolve(next).hasPermission("ticket.handle"))
                throw new BizException("该用户有未结工单，请选择有效的交接人");
            moved=tickets.transferAssignee(id,next.getId(),next.getName());
        }
        String before = "角色="+target.getRole()+"，状态="+target.getStatus()+"，部门="+target.getDeptName();
        target.setRole(req.getRole()); target.setStatus(req.getStatus());
        target.setDeptId(req.getDeptId()); target.setDeptName(dept==null ? "" : dept.getName());
        target.setSkill(req.getSkill()); target.setLocation(req.getLocation());
        users.adminUpdate(target);
        audit.record("USER_UPDATE",String.valueOf(id),target.getName()+"；原"+before+"；新角色="+target.getRole()+"，状态="+target.getStatus()+"，部门="+target.getDeptName()+"；转派工单="+moved);
        return users.findById(id);
    }
    @Transactional
    public Dept saveDept(Long id, DeptAdminReq req) {
        lockManagement("dept.manage");
        Map<Long,Dept> all=new HashMap<>(); depts.listAll().forEach(d -> all.put(d.getId(),d));
        if (id!=null && !all.containsKey(id)) throw new BizException(404,"部门不存在");
        Long parent=req.getParentId(); Set<Long> seen=new HashSet<>();
        while (parent!=0) {
            if (parent.equals(id) || !seen.add(parent)) throw new BizException("上级部门不能形成循环");
            Dept p=all.get(parent); if (p==null) throw new BizException("上级部门不存在"); parent=p.getParentId();
        }
        String name=req.getName().trim();
        if (all.values().stream().anyMatch(d -> !d.getId().equals(id) && name.equals(d.getName()) && req.getParentId().equals(d.getParentId())))
            throw new BizException("同一上级下已有同名部门");
        Dept d=id==null ? new Dept() : all.get(id);
        d.setName(name); d.setParentId(req.getParentId()); d.setSort(req.getSort());
        if (id==null) depts.insert(d); else { depts.adminUpdate(d); depts.renameUsers(id,name); }
        audit.record(id==null?"DEPT_CREATE":"DEPT_UPDATE",String.valueOf(d.getId()),name+"；上级="+d.getParentId());
        return d;
    }
    @Transactional
    public void deleteDept(Long id) {
        lockManagement("dept.manage"); Dept d=depts.findById(id);
        if (d==null) throw new BizException(404,"部门不存在");
        if (d.getDingDeptId()!=null) throw new BizException("钉钉同步部门请在钉钉通讯录中删除");
        if (depts.references(id)>0) throw new BizException("请先迁移部门成员和下级部门");
        depts.delete(id); audit.record("DEPT_DELETE",String.valueOf(id),d.getName());
    }
    @Transactional
    public SolutionCode saveSolution(Long id, SolutionAdminReq req) {
        lockManagement("dict.manage");
        SolutionCode code=id==null ? new SolutionCode() : solutions.findById(id);
        if (code==null) throw new BizException(404,"解决代码不存在");
        String value=req.getCode().trim(), category=req.getCategory().trim();
        if (id!=null && !value.equals(code.getCode())) throw new BizException("已有解决代码的标识不能修改，请新增代码");
        if (solutions.listAll().stream().anyMatch(c -> !c.getId().equals(id) && value.equals(c.getCode()) && category.equals(c.getCategory()))) throw new BizException("该分类下的代码已存在");
        code.setCode(value); code.setCategory(category); code.setName(req.getName().trim()); code.setSort(req.getSort());
        if(id==null) solutions.insert(code); else solutions.update(code);
        audit.record("SOLUTION_SAVE",String.valueOf(code.getId()),category+" / "+value+" / "+code.getName());
        return code;
    }
    @Transactional
    public void deleteSolution(Long id) {
        lockManagement("dict.manage"); SolutionCode code=solutions.findById(id);
        if(code==null) throw new BizException(404,"解决代码不存在");
        if(solutions.references(code.getCode())>0) throw new BizException("该代码已用于工单，请保留历史字典");
        solutions.delete(id); audit.record("SOLUTION_DELETE",String.valueOf(id),code.getCode()+" / "+code.getName());
    }
}
