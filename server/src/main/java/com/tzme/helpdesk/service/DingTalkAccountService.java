package com.tzme.helpdesk.service;

import com.tzme.helpdesk.common.BizException;
import com.tzme.helpdesk.config.HelpdeskProperties;
import com.tzme.helpdesk.domain.User;
import com.tzme.helpdesk.mapper.DeptMapper;
import com.tzme.helpdesk.mapper.ManagementMapper;
import com.tzme.helpdesk.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 首次免登按真实 userid 建档，与通讯录同步、人员管理共用锁，避免并发重复建档或覆盖角色。 */
@Service
@DependsOn("managementSchemaInitializer")
@RequiredArgsConstructor
public class DingTalkAccountService {
    private static final String BOOTSTRAP_SETTING = "dingtalk-bootstrap-admin";
    private final UserMapper users;
    private final DeptMapper depts;
    private final ManagementMapper management;
    private final HelpdeskProperties props;
    private final AuditService audit;

    @Transactional
    public User resolve(String userId, DingTalkService.UserProfile profile) {
        // 外部资料在进入本事务前获取，不在网络请求期间持有数据库锁。
        management.lockSetting("management-lock");
        User user = users.findByDingId(userId);
        if (user == null) {
            if (profile == null || !userId.equals(profile.userId())) {
                throw new BizException(401, "缺少经过钉钉验证的人员资料，请重新登录");
            }
            user = new User();
            user.setDingUserId(userId);
            user.setName(profile.name());
            user.setEmployeeNo(profile.employeeNo());
            user.setRole("USER");
            user.setStatus("ACTIVE");
            user.setDeptId(0L);
            // 已同步的部门立即关联；未同步时允许先进入，完整通讯录同步再补齐部门。
            for (Long deptId : profile.departmentIds()) {
                var dept = depts.findByDingId(String.valueOf(deptId));
                if (dept != null) {
                    user.setDeptId(dept.getId());
                    user.setDeptName(dept.getName());
                    break;
                }
            }
            users.insert(user);
            audit.record("DINGTALK_USER_REGISTER", String.valueOf(user.getId()), "首次钉钉免登建档，角色：普通用户");
        }
        if (!"ACTIVE".equals(user.getStatus())) throw new BizException(403, "账号已停用");

        String initialAdmin = props.getDingtalk().getBootstrapAdminUserId().trim();
        if (!initialAdmin.isEmpty() && initialAdmin.equals(userId) && management.setting(BOOTSTRAP_SETTING) == null
                && !com.tzme.helpdesk.security.Roles.SUPER_ADMIN.equals(user.getRole())) {
            user.setRole("ADMIN");
            users.adminUpdate(user);
            management.createSetting(BOOTSTRAP_SETTING, String.valueOf(user.getId()));
            audit.record("DINGTALK_ADMIN_BOOTSTRAP", String.valueOf(user.getId()), "通过服务端配置与钉钉认证初始化管理员（仅一次）");
        }
        return user;
    }
}
