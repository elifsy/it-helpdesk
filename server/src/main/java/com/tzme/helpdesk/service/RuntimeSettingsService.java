package com.tzme.helpdesk.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tzme.helpdesk.common.BizException;
import com.tzme.helpdesk.config.HelpdeskProperties;
import com.tzme.helpdesk.dto.DutySettings;
import com.tzme.helpdesk.dto.RuntimeSettings;
import com.tzme.helpdesk.mapper.ManagementMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import com.tzme.helpdesk.support.TicketCategories;
import java.util.*;

@Service
@DependsOn("managementSchemaInitializer")
@RequiredArgsConstructor
public class RuntimeSettingsService {
    private final ManagementMapper mapper;
    private final ObjectMapper json;
    private final HelpdeskProperties props;
    private final AuditService audit;

    public RuntimeSettings get() {
        String saved = mapper.setting("runtime");
        if (saved != null) return read(saved);
        RuntimeSettings defaults = new RuntimeSettings();
        defaults.setP1Hours(props.getSla().getP1Hours()); defaults.setP2Hours(props.getSla().getP2Hours());
        defaults.setP3Hours(props.getSla().getP3Hours()); defaults.setP4Hours(props.getSla().getP4Hours());
        defaults.setQrcodeBaseUrl(props.getQrcode().getBaseUrl());
        return normalize(defaults);
    }

    @Transactional
    public RuntimeSettings save(RuntimeSettings input) {
        com.tzme.helpdesk.security.Permissions.require("settings.manage");
        mapper.createSetting("runtime", write(get()));
        RuntimeSettings previous = read(mapper.lockSetting("runtime"));
        if (!previous.getRevision().equals(input.getRevision())) throw new BizException(409,"配置已被其他管理员修改，请刷新后重试");
        if (input.getDuty() == null) input.setDuty(previous.getDuty());
        boolean legacyChanged = !Objects.equals(previous.getCategories(), input.getCategories());
        boolean typedChanged = input.getCategoriesByType() != null && !Objects.equals(previous.getCategoriesByType(), input.getCategoriesByType());
        if (legacyChanged || typedChanged) com.tzme.helpdesk.security.Permissions.require("dict.manage");
        if (legacyChanged && !typedChanged) input.setCategoriesByType(null);
        else if (input.getCategoriesByType() == null) input.setCategoriesByType(previous.getCategoriesByType());
        validate(input);
        input.setRevision(previous.getRevision()+1);
        mapper.updateSetting("runtime", write(input));
        audit.record("SETTINGS_UPDATE", "runtime", "配置版本 " + previous.getRevision() + " → " + input.getRevision()
                + "；修改前：" + write(previous) + "；修改后：" + write(input));
        return input;
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
