package com.tzme.helpdesk.service;

import com.tzme.helpdesk.domain.Dept;
import com.tzme.helpdesk.domain.User;
import com.tzme.helpdesk.mapper.DeptMapper;
import com.tzme.helpdesk.mapper.TicketMapper;
import com.tzme.helpdesk.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 钉钉通讯录同步。
 *
 * 三种模式：
 *   dry  —— 用内置样例数据只算不写，看一眼会新增/停用多少人
 *   demo —— 用内置样例数据真实写库，本地没有钉钉凭据时验证整条逻辑
 *   live —— 调钉钉开放平台拉真实部门与用户
 *
 * 离职处理：本地 ACTIVE 且有钉钉 id、但本次同步没出现的，视为离职 → 停用 + 未结工单转派。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DingTalkContactService {

    public record DingUser(String userid, String name, String mobile, String email) {
    }

    public record DingDept(long id, String name, long parentId, List<DingUser> users) {
    }

    private record Handover(Long id, String name) {
    }

    private final DingTalkService dingTalk;
    private final DeptMapper deptMapper;
    private final UserMapper userMapper;
    private final TicketMapper ticketMapper;
    private final AuditService audit;
    private final com.tzme.helpdesk.config.HelpdeskProperties props;
    private final com.tzme.helpdesk.mapper.ManagementMapper management;
    private final RolePermissionService rolePermissions;

    @Transactional
    public Map<String, Object> sync(String mode) {
        String m = mode == null ? "live" : mode.toLowerCase();
        if (!List.of("live","dry","demo").contains(m)) throw new com.tzme.helpdesk.common.BizException("不支持的同步模式");
        if ("demo".equals(m) && props.getDingtalk().isEnabled()) throw new com.tzme.helpdesk.common.BizException("正式钉钉环境禁止写入样例通讯录");
        boolean live = "live".equals(m);
        boolean write = !"dry".equals(m);
        if (write) management.lockSetting("management-lock");

        List<DingDept> tree = live ? fetchFromDingTalk() : sampleTree();

        int deptCreated = 0;
        int deptUpdated = 0;
        int userCreated = 0;
        int userUpdated = 0;
        int disabled = 0;
        int transferred = 0;
        List<String> disabledNames = new ArrayList<>();
        Set<String> seenUserIds = new HashSet<>();
        Map<Long, Long> dingToLocal = new HashMap<>();

        for (DingDept d : tree) {
            Long parentLocal = d.parentId() <= 1 ? 0L : dingToLocal.getOrDefault(d.parentId(), 0L);
            Dept local = deptMapper.findByDingId(String.valueOf(d.id()));
            boolean isNew = false;

            if (local == null) {
                Dept sameName = deptMapper.findByName(d.name());
                if (sameName != null && sameName.getDingDeptId() == null) {
                    // 本地种子部门同名，绑上钉钉 id，避免同步出重复部门
                    if (write) {
                        deptMapper.bindDingId(sameName.getId(), String.valueOf(d.id()));
                    }
                    sameName.setDingDeptId(String.valueOf(d.id()));
                    local = sameName;
                    deptUpdated++;
                } else {
                    Dept nd = new Dept();
                    nd.setDingDeptId(String.valueOf(d.id()));
                    nd.setName(d.name());
                    nd.setParentId(parentLocal);
                    nd.setSort(0);
                    if (write) {
                        deptMapper.insert(nd);
                    } else {
                        nd.setId(-d.id());
                    }
                    local = nd;
                    deptCreated++;
                    isNew = true;
                }
            } else {
                if (write && (!d.name().equals(local.getName()) || !parentLocal.equals(local.getParentId()))) {
                    local.setName(d.name());
                    local.setParentId(parentLocal);
                    deptMapper.update(local);
                }
                deptUpdated++;
            }
            dingToLocal.put(d.id(), local.getId());

            for (DingUser u : d.users()) {
                User existing = userMapper.findByDingId(u.userid());
                if (existing == null) {
                    userCreated++;
                } else {
                    userUpdated++;
                }
                seenUserIds.add(u.userid());
                if (write) {
                    User nu = new User();
                    nu.setDingUserId(u.userid());
                    nu.setName(u.name());
                    nu.setDeptId(local.getId());
                    nu.setDeptName(local.getName());
                    nu.setMobile(u.mobile());
                    nu.setEmail(u.email());
                    userMapper.upsertByDing(nu);
                }
            }
            if (isNew) {
                log.debug("dept created: {} ({})", d.name(), d.id());
            }
        }

        // 离职：本地在职、有钉钉 id、但本次没同步到
        for (User u : userMapper.listActiveWithDing()) {
            if (seenUserIds.contains(u.getDingUserId())) {
                continue;
            }
            disabled++;
            disabledNames.add(u.getName());
            if (!write) {
                continue;
            }
            userMapper.disable(u.getId());
            Handover h = pickHandover(u.getId());
            if (h != null) {
                transferred += ticketMapper.transferAssignee(u.getId(), h.id(), h.name());
            }
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("mode", m);
        r.put("departments", Map.of("created", deptCreated, "updated", deptUpdated));
        r.put("users", Map.of("created", userCreated, "updated", userUpdated));
        r.put("disabled", disabled);
        r.put("disabledNames", disabledNames);
        r.put("transferredTickets", transferred);
        r.put("finishedAt", LocalDateTime.now());
        if(write) audit.record("CONTACT_SYNC",m,"新增人员="+userCreated+"，更新="+userUpdated+"，停用="+disabled+"，转派="+transferred);
        log.info("contact sync done: mode={} dept+{} ~{} user+{} ~{} disabled={} moved={}",
                m, deptCreated, deptUpdated, userCreated, userUpdated, disabled, transferred);
        return r;
    }

    /** 离职人员的未结工单交给谁：优先 IT 主管，其次服务台，最后任一工程师 */
    private Handover pickHandover(Long excludeId) {
        for (String role : List.of("IT", "ADMIN")) {
            for (User u : userMapper.listByRole(role)) {
                if (!u.getId().equals(excludeId) && !"DISABLED".equals(u.getStatus()) && rolePermissions.resolve(u).hasPermission("ticket.handle")) {
                    return new Handover(u.getId(), u.getName());
                }
            }
        }
        return null;
    }

    private List<DingDept> fetchFromDingTalk() {
        List<DingDept> out = new ArrayList<>();
        Deque<Long> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        queue.add(1L);
        while (!queue.isEmpty()) {
            long parent = queue.poll();
            if (!visited.add(parent)) {
                continue;
            }
            for (Map<String, Object> sub : dingTalk.listSubDepartments(parent)) {
                Object idObj = sub.get("dept_id");
                if (!(idObj instanceof Number n)) {
                    continue;
                }
                long id = n.longValue();
                String name = String.valueOf(sub.getOrDefault("name", "未命名部门"));
                queue.add(id);
                List<DingUser> users = new ArrayList<>();
                for (Map<String, Object> row : dingTalk.listDeptUsers(id)) {
                    String userid = String.valueOf(row.get("userid"));
                    if (userid == null || "null".equals(userid)) {
                        continue;
                    }
                    users.add(new DingUser(
                            userid,
                            String.valueOf(row.getOrDefault("name", userid)),
                            row.get("mobile") == null ? null : String.valueOf(row.get("mobile")),
                            row.get("email") == null ? null : String.valueOf(row.get("email"))));
                }
                out.add(new DingDept(id, name, parent, users));
            }
        }
        return out;
    }

    /** 本地演示用样例：与种子部门同名，验证「按名称认领」；含一名新入职，不含测试离职人员 */
    private List<DingDept> sampleTree() {
        return List.of(
                new DingDept(1001, "信息技术部", 1, List.of(
                        new DingUser("ding_wangmin", "王敏", "13800000002", "wangmin@tzme.net"),
                        new DingUser("ding_liqiang", "李强", "13800000003", "liqiang@tzme.net"),
                        new DingUser("ding_sunpeng", "孙鹏", "13800000004", "sunpeng@tzme.net"),
                        new DingUser("ding_chenjing", "陈静", "13800000006", "chenjing@tzme.net"))),
                new DingDept(1002, "技术中心", 1, List.of(
                        new DingUser("ding_zhangwei", "张伟", "13800000001", "zhangwei@tzme.net"),
                        new DingUser("ding_zhoumin", "周敏", "13800000099", "zhoumin@tzme.net"))),
                new DingDept(1003, "生产制造部", 1, List.of(
                        new DingUser("ding_liuyang", "刘洋", "13800000007", "liuyang@tzme.net"))),
                new DingDept(1004, "质量管理部", 1, List.of(
                        new DingUser("ding_wangfang", "王芳", "13800000008", "wangfang@tzme.net"))),
                new DingDept(1005, "财务部", 1, List.of(
                        new DingUser("ding_zhaomin", "赵敏", "13800000005", "zhaomin@tzme.net")))
        );
    }
}
