package com.tzme.helpdesk.service;

import com.tzme.helpdesk.common.BizException;
import com.tzme.helpdesk.config.HelpdeskProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 钉钉开放平台适配层：access_token 集中缓存，避免多实例互相顶掉（40014）。
 */
@Slf4j
@Service
public class DingTalkService {
    public record LeavePeriod(String userId, Instant start, Instant end) { }

    public List<LeavePeriod> leavePeriods(List<String> userIds, Instant start, Instant end) {
        if (userIds.isEmpty()) return List.of();
        String url = props.getDingtalk().getLeaveStatusUrl() + "?access_token=" + accessToken();
        int size = 20;
        List<LeavePeriod> periods = new ArrayList<>();
        for (int offset = 0; ; offset += size) {
            var body = post(url, Map.of("userid_list", String.join(",", userIds), "start_time", start.toEpochMilli(),
                    "end_time", end.toEpochMilli(), "offset", offset, "size", size));
            if (body == null || num(body.get("errcode")) != 0 || Boolean.FALSE.equals(body.get("success"))
                    || !(body.get("result") instanceof Map<?, ?> result)
                    || !(result.get("has_more") instanceof Boolean more)) {
                if (body != null) log.warn("DingTalk leave status lookup unsuccessful, code {}", num(body.get("errcode")));
                throw new BizException(502, "钉钉请假状态暂不可用");
            }
            Object entries = result.get("leave_status");
            if (entries == null && !more) return periods;
            if (!(entries instanceof List<?> rows) || (rows.isEmpty() && more))
                throw new BizException(502, "钉钉请假状态响应不完整");
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> entry) || !(entry.get("userid") instanceof String userId))
                    throw new BizException(502, "钉钉请假状态响应不完整");
                long from = num(entry.get("start_time")), to = num(entry.get("end_time"));
                if (from <= 0 || to <= from) throw new BizException(502, "钉钉请假时间无效");
                if (userIds.contains(userId)) periods.add(new LeavePeriod(userId, Instant.ofEpochMilli(from), Instant.ofEpochMilli(to)));
            }
            if (!more) return periods;
        }
    }

    public record UserProfile(String userId, String name, String employeeNo, List<Long> departmentIds) { }

    private static final String TOKEN_KEY = "helpdesk:dingtalk:access_token";

    private final HelpdeskProperties props;
    private final StringRedisTemplate redis;
    private final RestTemplate rest;
    private volatile String localToken;
    private volatile long localTokenExpires;

    public DingTalkService(HelpdeskProperties props, StringRedisTemplate redis) {
        this.props = props;
        this.redis = redis;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3000);
        f.setReadTimeout(5000);
        this.rest = new RestTemplate(f);
    }

    public boolean enabled() {
        return props.getDingtalk().isEnabled();
    }

    public String accessToken() {
        String cached = readCachedToken();
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        synchronized (this) {
            cached = readCachedToken();
            if (cached != null && !cached.isBlank()) {
                return cached;
            }
            String url = props.getDingtalk().getTokenUrl()
                    + "?appkey=" + props.getDingtalk().getAppKey()
                    + "&appsecret=" + props.getDingtalk().getAppSecret();
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = rest.getForObject(url, Map.class);
            if (resp == null || num(resp.get("errcode")) != 0) {
                throw new BizException(502, "获取钉钉 access_token 失败：" + (resp == null ? "空响应" : resp.get("errmsg")));
            }
            String token = String.valueOf(resp.get("access_token"));
            long expires = num(resp.get("expires_in"));
            // 提前 5 分钟过期，避免临界失效
            long ttl = Math.max(120, expires - 300);
            localToken = token;
            localTokenExpires = System.currentTimeMillis() + ttl * 1000;
            if (props.isRedisEnabled()) {
                try { redis.opsForValue().set(TOKEN_KEY, token, Duration.ofSeconds(ttl)); }
                catch (Exception e) { log.warn("cache DingTalk token locally: Redis unavailable"); }
            }
            return token;
        }
    }

    private String readCachedToken() {
        if (localToken != null && System.currentTimeMillis() < localTokenExpires) return localToken;
        if (!props.isRedisEnabled()) return null;
        try { return redis.opsForValue().get(TOKEN_KEY); }
        catch (Exception e) { log.warn("read DingTalk token cache failed: Redis unavailable"); return null; }
    }

    /** authCode -> 钉钉 userid */
    public String getUserIdByCode(String authCode) {
        if (authCode == null || authCode.isBlank()) {
            throw new BizException(401, "缺少钉钉免登授权码");
        }
        String url = props.getDingtalk().getUserInfoUrl() + "?access_token=" + accessToken();
        Map<String, Object> body = new HashMap<>();
        body.put("code", authCode);
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = post(url, body);
        if (resp == null || num(resp.get("errcode")) != 0) {
            throw new BizException(401, "钉钉免登失败：" + (resp == null ? "空响应" : resp.get("errmsg")));
        }
        Object result = resp.get("result");
        if (!(result instanceof Map<?, ?> r) || !(r.get("userid") instanceof String id) || id.isBlank()) {
            throw new BizException(401, "钉钉未返回 userid");
        }
        return id;
    }

    /** 仅使用免登接口验证过的 userid 读取真实资料，不接收客户端提供的姓名或角色。 */
    public UserProfile getUserDetail(String userId) {
        Map<String, Object> resp;
        try {
            resp = post(props.getDingtalk().getUserDetailUrl() + "?access_token=" + accessToken(),
                    Map.of("userid", userId, "language", "zh_CN"));
        } catch (RestClientException e) {
            // HTTP 异常可能含 access_token，不向页面或日志输出原始异常。
            throw new BizException(502, "读取钉钉人员资料暂时失败，请稍后重试");
        }
        if (resp == null || num(resp.get("errcode")) != 0) {
            throw new BizException(502, userProfileError(resp));
        }
        if (!(resp.get("result") instanceof Map<?, ?> result)
                || !userId.equals(result.get("userid"))
                || !(result.get("name") instanceof String name) || name.isBlank()) {
            throw new BizException(502, "钉钉返回的人员资料不完整或账号不一致，暂时无法登录");
        }
        List<Long> departments = new ArrayList<>();
        if (result.get("dept_id_list") instanceof List<?> ids) {
            for (Object id : ids) {
                long deptId = num(id);
                if (deptId > 0 && !departments.contains(deptId)) departments.add(deptId);
            }
        }
        String employeeNo = result.get("job_number") instanceof String number ? number : null;
        return new UserProfile(userId, name, employeeNo, List.copyOf(departments));
    }

    private String userProfileError(Map<String, Object> response) {
        if (response == null) return "钉钉认证已通过，但读取人员资料返回空响应，请稍后重试";
        long outer = num(response.get("errcode"));
        long cause = outer;
        if (outer == 88) {
            // 88 是外层错误，实际原因在子错误中；兼容钉钉不同接口的命名格式。
            for (String key : List.of("sub_code", "subCode", "subcode")) {
                long value = num(response.get(key));
                if (value >= 0) { cause = value; break; }
            }
            if (cause == 88 && response.get("errmsg") instanceof String message) {
                var match = java.util.regex.Pattern.compile("sub_?code\\s*[=:]\\s*[\"']?(\\d+)",
                        java.util.regex.Pattern.CASE_INSENSITIVE).matcher(message);
                if (match.find()) cause = num(match.group(1));
            }
        }
        String codes = String.valueOf(outer) + (cause != outer ? "/" + cause : "");
        // 仅使用错误码生成提示，不输出可能含 access_token 或人员资料的原始错误信息。
        if (cause == 60011) {
            return "钉钉认证已通过，但应用缺少成员信息读取权限（qyapi_get_member，错误码 " + codes
                    + "）。请钉钉管理员在开发者后台进入本应用 → 权限管理，开通该权限并确认通讯录授权范围包含本人，再重新尝试";
        }
        return "钉钉认证已通过，但读取人员资料失败（错误码 " + codes
                + "），请管理员根据此错误码检查钉钉接口配置后重试";
    }

    public String workAppLink(String target) {
        var app = props.getDingtalk();
        if (app.getCorpId().isBlank() || app.getAgentId().isBlank()) return null;
        return "dingtalk://dingtalkclient/action/openapp?corpid=" + encode(app.getCorpId())
                + "&container_type=work_platform&app_id=" + encode("0_" + app.getAgentId())
                + "&redirect_type=jump&redirect_url=" + encode(target);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** 工单通知使用可跳转的 OA 卡片。其他无链接通知继续使用 Markdown。 */
    public void sendWorkNotify(String dingUserIds, String title, String content, String url) {
        if (!enabled() || dingUserIds == null || dingUserIds.isBlank()) {
            return;
        }
        try {
            String markdown = "### " + title + "\n\n" + content;
            Map<String, Object> msg = url == null || url.isBlank()
                    ? Map.of("msgtype", "markdown", "markdown", Map.of("title", title, "text", markdown))
                    : Map.of("msgtype", "oa", "oa", Map.of(
                            "message_url", url, "pc_message_url", url,
                            "head", Map.of("bgcolor", "FF0071E3", "text", "IT 服务台"),
                            "body", Map.of("title", title, "content", "点击查看工单\n" + content)));

            Map<String, Object> body = new HashMap<>();
            body.put("agent_id", props.getDingtalk().getAgentId());
            body.put("userid_list", dingUserIds);
            body.put("msg", msg);

            @SuppressWarnings("unchecked")
            Map<String, Object> resp = post(props.getDingtalk().getWorkNotifyUrl() + "?access_token=" + accessToken(), body);
            if (resp == null || num(resp.get("errcode")) != 0) {
                log.warn("send work notify failed: {}", resp == null ? "null" : resp.get("errmsg"));
            }
        } catch (Exception e) {
            log.warn("send work notify error: {}", e.getMessage());
        }
    }

    /** 拉取子部门列表（dept_id=1 为企业根部门） */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listSubDepartments(long parentId) {
        String url = props.getDingtalk().getDeptListUrl()
                + "?access_token=" + accessToken() + "&dept_id=" + parentId;
        Map<String, Object> resp = rest.getForObject(url, Map.class);
        if (resp == null || num(resp.get("errcode")) != 0) {
            throw new BizException(502, "获取钉钉部门失败：" + (resp == null ? "空响应" : resp.get("errmsg")));
        }
        Object result = resp.get("result");
        if (result instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return new ArrayList<>();
    }

    /** 拉取部门下全部用户（自动翻页） */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listDeptUsers(long deptId) {
        List<Map<String, Object>> all = new ArrayList<>();
        String url = props.getDingtalk().getUserListUrl() + "?access_token=" + accessToken();
        long cursor = 0;
        for (int guard = 0; guard < 200; guard++) {
            Map<String, Object> body = new HashMap<>();
            body.put("dept_id", deptId);
            body.put("cursor", cursor);
            body.put("size", 100);
            Map<String, Object> resp = post(url, body);
            if (resp == null || num(resp.get("errcode")) != 0) {
                throw new BizException(502, "获取钉钉部门用户失败：" + (resp == null ? "空响应" : resp.get("errmsg")));
            }
            if (!(resp.get("result") instanceof Map<?, ?> result)) {
                break;
            }
            Object list = result.get("list");
            if (list instanceof List<?> rows) {
                all.addAll((List<Map<String, Object>>) rows);
            }
            Boolean hasMore = (Boolean) result.get("has_more");
            if (hasMore == null || !hasMore) {
                break;
            }
            Object next = result.get("next_cursor");
            cursor = next instanceof Number n ? n.longValue() : 0;
        }
        return all;
    }

    /** 发起钉钉审批实例（新版 API），返回 instanceId */
    @SuppressWarnings("unchecked")
    public String createApprovalInstance(String processCode, String originatorUserId, Long deptId,
                                         java.util.List<Map<String, Object>> formValues) {
        if (!enabled()) throw new com.tzme.helpdesk.common.BizException(503, "钉钉未启用，无法发起钉钉审批");
        if (processCode == null || processCode.isBlank())
            throw new com.tzme.helpdesk.common.BizException("未配置该业务的钉钉审批模板");
        Map<String, Object> body = new HashMap<>();
        body.put("processCode", processCode);
        body.put("originatorUserId", originatorUserId);
        if (deptId != null && deptId > 0) body.put("deptId", deptId);
        String agent = props.getDingtalk().getAgentId();
        if (agent != null && !agent.isBlank()) {
            try { body.put("microappAgentId", Long.parseLong(agent.trim())); } catch (NumberFormatException ignore) { }
        }
        body.put("formComponentValues", formValues);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-acs-dingtalk-access-token", accessToken());
        Map<String, Object> resp;
        try {
            resp = rest.postForObject(props.getDingtalk().getApprovalUrl(), new HttpEntity<>(body, headers), Map.class);
        } catch (Exception e) {
            throw new com.tzme.helpdesk.common.BizException(502, "发起钉钉审批失败：" + e.getMessage());
        }
        Object instanceId = resp == null ? null : resp.get("instanceId");
        if (instanceId == null || String.valueOf(instanceId).isBlank()) {
            throw new com.tzme.helpdesk.common.BizException(502, "发起钉钉审批失败："
                    + (resp == null ? "空响应" : String.valueOf(resp.get("message"))));
        }
        return String.valueOf(instanceId);
    }

    /** 查询钉钉审批实例：返回 result 节点（含 status/result），失败返回 null */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getApprovalInstance(String instanceId) {
        if (!enabled()) throw new com.tzme.helpdesk.common.BizException(503, "钉钉未启用");
        String url = props.getDingtalk().getApprovalUrl()
                + "?processInstanceId=" + java.net.URLEncoder.encode(instanceId, java.nio.charset.StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-acs-dingtalk-access-token", accessToken());
        try {
            Map<String, Object> resp = rest.exchange(url, org.springframework.http.HttpMethod.GET,
                    new HttpEntity<>(headers), Map.class).getBody();
            if (resp == null) return null;
            Object result = resp.get("result");
            return result instanceof Map ? (Map<String, Object>) result : resp;
        } catch (Exception e) {
            log.warn("get dingtalk approval instance failed: {} - {}", instanceId, e.getMessage());
            return null;
        }
    }

    /** 按审批模板名称查 processCode；查不到抛业务异常并带上钉钉原因 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> findProcessByName(String name) {
        if (!enabled()) throw new com.tzme.helpdesk.common.BizException(503, "钉钉未启用，无法查询审批模板");
        if (name == null || name.isBlank()) throw new com.tzme.helpdesk.common.BizException("请填写钉钉审批模板名称");
        Map<String, Object> body = new HashMap<>();
        body.put("name", name.trim());
        Map<String, Object> resp;
        try {
            resp = post(props.getDingtalk().getProcessByNameUrl() + "?access_token=" + accessToken(), body);
        } catch (Exception e) {
            throw new com.tzme.helpdesk.common.BizException(502, "调用钉钉接口失败：" + e.getMessage());
        }
        if (resp == null) throw new com.tzme.helpdesk.common.BizException(502, "钉钉返回空响应");
        long code = num(resp.get("errcode"));
        if (code != 0) {
            String msg = String.valueOf(resp.get("errmsg"));
            throw new com.tzme.helpdesk.common.BizException(502, "钉钉未找到该模板：" + msg + "（请核对模板名称，并确认应用已开通审批权限）");
        }
        Object result = resp.get("result");
        if (!(result instanceof Map)) throw new com.tzme.helpdesk.common.BizException(502, "钉钉未返回模板信息");
        return (Map<String, Object>) result;
    }

    private Map<String, Object> post(String url, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = rest.postForObject(url, new HttpEntity<>(body, headers), Map.class);
        return resp;
    }

    private long num(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return -1;
        }
    }
}
