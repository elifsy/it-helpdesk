package com.tzme.helpdesk.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tzme.helpdesk.common.BizException;
import com.tzme.helpdesk.common.CurrentUser;
import com.tzme.helpdesk.config.HelpdeskProperties;
import com.tzme.helpdesk.dto.DutySettings;
import com.tzme.helpdesk.dto.JobSettings;
import com.tzme.helpdesk.dto.RuntimeSettings;
import com.tzme.helpdesk.dto.SlaCalendar;
import com.tzme.helpdesk.mapper.ManagementMapper;
import com.tzme.helpdesk.security.Permissions;
import com.tzme.helpdesk.security.Roles;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import com.tzme.helpdesk.support.TicketCategories;
import java.util.*;

@Service
@DependsOn("managementSchemaInitializer")
@RequiredArgsConstructor
public class RuntimeSettingsService {
    private static final Set<String> DELEGATED_FIELDS = Set.of(
            "revision", "duty", "handoverCategories", "approvalEnabled", "approvalMock",
            "approvalPollMinutes", "approvalProcessCodes", "approvalMappings", "categories", "categoriesByType");
    private final ManagementMapper mapper;
    private final ObjectMapper json;
    private final Validator validator;
    private final HelpdeskProperties props;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public RuntimeSettings get() {
        String saved = mapper.setting("runtime");
        if (saved != null) return read(saved);
        RuntimeSettings defaults = new RuntimeSettings();
        defaults.setP1Hours(props.getSla().getP1Hours()); defaults.setP2Hours(props.getSla().getP2Hours());
        defaults.setP3Hours(props.getSla().getP3Hours()); defaults.setP4Hours(props.getSla().getP4Hours());
        defaults.setQrcodeBaseUrl(props.getQrcode().getBaseUrl());
        return normalize(defaults);
    }

    private ObjectNode generalSettings(RuntimeSettings value) {
        ObjectNode result = json.valueToTree(value);
        result.remove(List.of("slaScanEnabled", "contactSyncEnabled", "slaCron", "contactCron", "slaReminderHours", "confirmationReminderHours"));
        return result;
    }

    public ObjectNode managementView(RuntimeSettings value) {
        ObjectNode result = generalSettings(value);
        if (!CurrentUser.require().hasRole(Roles.SUPER_ADMIN)) result.retain(DELEGATED_FIELDS);
        return result;
    }

    @Transactional
    public RuntimeSettings save(ObjectNode update) {
        Permissions.require("settings.manage");
        boolean superAdmin = CurrentUser.require().hasRole(Roles.SUPER_ADMIN);
        if (!superAdmin) {
            update.fieldNames().forEachRemaining(field -> {
                if (!DELEGATED_FIELDS.contains(field)) throw new BizException(403,"仅超级管理员可修改该配置");
            });
            if (!update.hasNonNull("revision")) throw new BizException("请重新加载配置再保存");
        }
        mapper.createSetting("runtime", write(get()));
        RuntimeSettings previous = read(mapper.lockSetting("runtime"));
        // 委派配置合并到锁定的当前版本，避免未返回的字段被 DTO 默认值覆盖。
        ObjectNode value = superAdmin ? update : json.<ObjectNode>valueToTree(previous).setAll(update);
        RuntimeSettings input = readUpdate(value);
        if (!previous.getRevision().equals(input.getRevision())) throw new BizException(409,"配置已被其他管理员修改，请刷新后重试");
        input.setSlaScanEnabled(previous.getSlaScanEnabled());
        input.setContactSyncEnabled(previous.getContactSyncEnabled());
        input.setSlaCron(previous.getSlaCron());
        input.setContactCron(previous.getContactCron());
        input.setSlaReminderHours(previous.getSlaReminderHours());
        input.setConfirmationReminderHours(previous.getConfirmationReminderHours());
        if (input.getDuty() == null) input.setDuty(previous.getDuty());
        if (input.getSlaCalendar() == null) input.setSlaCalendar(previous.getSlaCalendar());
        boolean legacyChanged = !Objects.equals(previous.getCategories(), input.getCategories());
        boolean typedChanged = input.getCategoriesByType() != null && !Objects.equals(previous.getCategoriesByType(), input.getCategoriesByType());
        if (legacyChanged || typedChanged) Permissions.require("dict.manage");
        if (legacyChanged && !typedChanged) input.setCategoriesByType(null);
        else if (input.getCategoriesByType() == null) input.setCategoriesByType(previous.getCategoriesByType());
        validate(input);
        input.setRevision(previous.getRevision()+1);
        mapper.updateSetting("runtime", write(input));
        audit.record("SETTINGS_UPDATE", "runtime", "配置版本 " + previous.getRevision() + " → " + input.getRevision()
                + "；修改前：" + generalSettings(previous) + "；修改后：" + generalSettings(input));
        return input;
    }

    private RuntimeSettings readUpdate(ObjectNode request) {
        try {
            RuntimeSettings input = json.treeToValue(request, RuntimeSettings.class);
            var invalid = validator.validate(input).stream().findFirst();
            if (invalid.isPresent()) {
                var violation = invalid.get();
                throw new BizException(violation.getPropertyPath() + " " + violation.getMessage());
            }
            return input;
        } catch (JsonProcessingException e) {
            throw new BizException("请求参数不合法");
        }
    }

    public JobSettings jobs() {
        requireJobAdmin();
        return jobSchedule();
    }

    public JobSettings jobSchedule() {
        return jobSettings(get());
    }

    @Transactional
    public JobSettings saveJobs(JobSettings input) {
        requireJobAdmin();
        String slaCron = validateCron("超时工单扫描", input.slaCron());
        String contactCron = validateCron("通讯录同步", input.contactCron());
        mapper.createSetting("runtime", write(get()));
        RuntimeSettings current = read(mapper.lockSetting("runtime"));
        if (!current.getRevision().equals(input.revision())) throw new BizException(409, "配置已被其他管理员修改，请重新加载后重试");
        current.setSlaScanEnabled(input.slaScanEnabled());
        current.setSlaCron(slaCron);
        current.setContactSyncEnabled(input.contactSyncEnabled());
        current.setContactCron(contactCron);
        if (input.slaReminderHours() != null) current.setSlaReminderHours(input.slaReminderHours());
        if (input.confirmationReminderHours() != null) current.setConfirmationReminderHours(input.confirmationReminderHours());
        current.setRevision(current.getRevision() + 1);
        mapper.updateSetting("runtime", write(current));
        JobSettings saved = jobSettings(current);
        audit.record("JOB_SETTINGS_UPDATE", "jobs", "配置版本 " + input.revision() + " → " + saved.revision());
        events.publishEvent(saved);
        return saved;
    }

    private JobSettings jobSettings(RuntimeSettings value) {
        return new JobSettings(value.getRevision(), value.getSlaScanEnabled(),
                Objects.requireNonNullElse(value.getSlaCron(), JobSettings.DEFAULT_SLA_CRON),
                value.getContactSyncEnabled(), Objects.requireNonNullElse(value.getContactCron(), props.getDingtalk().getSyncCron()),
                Objects.requireNonNullElse(value.getSlaReminderHours(), JobSettings.DEFAULT_SLA_REMINDER_HOURS),
                Objects.requireNonNullElse(value.getConfirmationReminderHours(), JobSettings.DEFAULT_CONFIRMATION_REMINDER_HOURS));
    }

    private void requireJobAdmin() {
        if (!CurrentUser.require().hasRole(Roles.SUPER_ADMIN)) throw new BizException(403, "仅超级管理员可管理定时任务");
    }

    private String validateCron(String label, String cron) {
        String value = cron.trim().replaceAll("\\s+", " ");
        if (!Scheduled.CRON_DISABLED.equals(value) && !CronExpression.isValidExpression(value)) {
            throw new BizException(label + "的 Cron 表达式无效，请填写有效的 Spring Cron 表达式");
        }
        return value;
    }

    @Transactional
    public java.util.Map<String,Object> saveCategories(com.tzme.helpdesk.dto.CategoriesReq input) {
        com.tzme.helpdesk.security.Permissions.require("dict.manage");
        mapper.createSetting("runtime",write(get()));
        RuntimeSettings current=read(mapper.lockSetting("runtime"));
        if(!current.getRevision().equals(input.getRevision())) throw new BizException(409,"字典已被其他用户修改，请重新加载");
        if (input.getCategoriesByType() == null && input.getCategories() == null) throw new BizException("请配置各问题类型的分类");
        current.setCategoriesByType(input.getCategoriesByType());
        if (input.getCategories() != null) current.setCategories(input.getCategories());
        validate(current);
        current.setRevision(current.getRevision()+1);
        mapper.updateSetting("runtime",write(current));
        audit.record("CATEGORIES_UPDATE","categories","版本="+current.getRevision()+"；分类="+current.getCategoriesByType());
        return java.util.Map.of("revision",current.getRevision(),"categories",current.getCategories(),"categoriesByType",current.getCategoriesByType());
    }

    public void validate(RuntimeSettings input) {
        SlaCalendar calendar = input.getSlaCalendar();
        if (!java.time.LocalTime.parse(calendar.start()).isBefore(java.time.LocalTime.parse(calendar.end())))
            throw new BizException("工作结束时间须晚于开始时间");
        var response = calendar.responseHours();
        for (int i = 1; i < response.size(); i++) {
            if (response.get(i) < response.get(i - 1)) throw new BizException("首次响应时限需满足 P1 ≤ P2 ≤ P3 ≤ P4");
        }
        if (!(input.getP1Hours() <= input.getP2Hours() && input.getP2Hours() <= input.getP3Hours() && input.getP3Hours() <= input.getP4Hours()))
            throw new BizException("SLA 时限需满足 P1 ≤ P2 ≤ P3 ≤ P4");
        try {
            URI url = URI.create(input.getQrcodeBaseUrl());
            if (!("http".equals(url.getScheme()) || "https".equals(url.getScheme())) || url.getHost()==null
                    || url.getUserInfo()!=null || url.getQuery()!=null || url.getFragment()!=null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) { throw new BizException("二维码基址需为有效的 HTTP/HTTPS 地址，且不含账号、查询或片段"); }
        if (input.getCategoriesByType() == null) {
            validateDictionary(input.getCategories());
            input.setCategoriesByType(TicketCategories.fromLegacy(input.getCategories()));
        }
        var byType = input.getCategoriesByType();
        if (!byType.keySet().equals(new HashSet<>(TicketCategories.TYPES))) throw new BizException("需分别配置故障报修、服务申请和咨询的分类");
        byType.values().forEach(this::validateDictionary);
        if (!byType.get("INCIDENT").containsKey("信息安全")) throw new BizException("故障报修必须保留信息安全分类");
        input.setCategories(TicketCategories.merge(byType));
        if (input.getCategories().size() > 30) throw new BizException("一级分类合计不能超过 30 个");
    }
    private void validateDictionary(Map<String,List<String>> categories) {
        if (categories == null || categories.isEmpty() || categories.size() > 30) throw new BizException("每种问题类型需配置 1–30 个分类");
        categories.forEach((name, children) -> {
            if (name==null || name.isBlank() || !name.equals(name.trim()) || name.length()>32 || children==null || children.isEmpty() || children.size()>50
                    || children.stream().anyMatch(c -> c==null || c.isBlank() || !c.equals(c.trim()) || c.length()>32)
                    || new java.util.HashSet<>(children).size()!=children.size()) throw new BizException("分类名称需为 1–32 字，每个分类需有不重复的子分类");
        });
    }
    private RuntimeSettings normalize(RuntimeSettings value) {
        if (value.getDuty() == null) value.setDuty(DutySettings.defaults());
        if (value.getSlaCalendar() == null) value.setSlaCalendar(SlaCalendar.defaults());
        if (value.getCategoriesByType() == null) value.setCategoriesByType(TicketCategories.fromLegacy(value.getCategories()));
        value.setCategories(TicketCategories.merge(value.getCategoriesByType()));
        return value;
    }
    private RuntimeSettings read(String value) {
        try { return normalize(json.readValue(value, RuntimeSettings.class)); }
        catch (JsonProcessingException e) { throw new IllegalStateException("读取运行配置失败",e); }
    }
    private String write(RuntimeSettings value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("保存运行配置失败",e); }
    }
}
