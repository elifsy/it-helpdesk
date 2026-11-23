package com.tzme.helpdesk.service;

import com.tzme.helpdesk.domain.AssetRequest;
import com.tzme.helpdesk.domain.PurchaseRequest;
import com.tzme.helpdesk.domain.User;
import com.tzme.helpdesk.dto.RuntimeSettings;
import com.tzme.helpdesk.mapper.AssetRequestMapper;
import com.tzme.helpdesk.mapper.PurchaseRequestMapper;
import com.tzme.helpdesk.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 钉钉审批发起侧：把资产申请推成一条钉钉审批实例。
 * 未开启、未配置模板、申请人没绑钉钉、接口失败时统一返回 false，
 * 由调用方降级走系统内审批，保证业务不中断。
 *
 * 表单字段名支持按业务单独配置（approvalMappings）：type -> (系统字段 key -> 钉钉控件名)。
 * 未配置的 key 用默认名；配置成 "-" 表示该字段不传给钉钉。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DingApprovalService {

    public static final String PURCHASE = "PURCHASE";

    private static final Map<String, String> TYPE_NAME = Map.of(
            "RECEIVE", "资产领用", "BORROW", "资产借用", "REPLACE", "资产更换", "RETURN", "归还设备",
            PURCHASE, "设备购入");

    /** 可映射的系统字段：key / 中文标签 / 默认钉钉控件名 */
    public record FormField(String key, String label, String defaultName) { }

    public static final List<FormField> FIELDS = List.of(
            new FormField("no", "申请单号", "申请单号"),
            new FormField("bizType", "业务类型", "业务类型"),
            new FormField("assetNo", "资产编号", "资产编号"),
            new FormField("assetName", "资产名称", "资产名称"),
            new FormField("assetCategory", "资产分类", "资产分类"),
            new FormField("applicant", "申请人", "申请人"),
            new FormField("dept", "部门", "部门"),
            new FormField("reason", "申请事由", "申请事由"),
            new FormField("expectReturn", "约定归还日期", "约定归还日期"),
            new FormField("newAssetNo", "替换设备编号", "替换设备编号")
    );

    /** 设备购入申请可映射的字段（与资产申请不同，没有资产编号/归还日期） */
    public static final List<FormField> PURCHASE_FIELDS = List.of(
            new FormField("no", "申请单号", "申请单号"),
            new FormField("bizType", "业务类型", "业务类型"),
            new FormField("itemName", "购买设备名称", "设备名称"),
            new FormField("category", "资产分类", "资产分类"),
            new FormField("applicant", "申请人", "申请人"),
            new FormField("dept", "部门", "部门"),
            new FormField("reason", "申请事由", "申请事由")
    );

    private final RuntimeSettingsService settings;
    private final DingTalkService dingTalk;
    private final AssetRequestMapper requestMapper;
    private final PurchaseRequestMapper purchaseMapper;
    private final UserMapper userMapper;

    public List<FormField> fieldCatalog() {
        return FIELDS;
    }

    /** 按业务类型返回可映射字段；PURCHASE 用采购专用清单 */
    public List<FormField> fieldCatalog(String type) {
        return PURCHASE.equals(type) ? PURCHASE_FIELDS : FIELDS;
    }

    /** 该业务是否配置了钉钉审批模板 */
    public boolean enabledFor(String type) {
        RuntimeSettings s = settings.get();
        if (!Boolean.TRUE.equals(s.getApprovalEnabled())) return false;
        Map<String, String> codes = s.getApprovalProcessCodes();
        if (codes == null) return false;
        String code = codes.get(type);
        return code != null && !code.isBlank();
    }

    public boolean mockMode() {
        return Boolean.TRUE.equals(settings.get().getApprovalMock());
    }

    /**
     * 尝试发起钉钉审批。成功时把 instanceId 写回申请单并返回 true；
     * 任何异常都降级为 false（调用方走系统内审批）。
     */
    @Transactional
    public boolean tryStart(AssetRequest r) {
        if (r == null || !enabledFor(r.getType())) return false;
        RuntimeSettings s = settings.get();
        try {
            if (Boolean.TRUE.equals(s.getApprovalMock())) {
                r.setDingInstanceId("MOCK-" + r.getNo());
                r.setDingStatus("RUNNING");
                r.setDingSyncedAt(LocalDateTime.now());
                r.setDingMessage("本地联调模式（未真实调用钉钉）");
                requestMapper.update(r);
                log.info("ding approval mock started: {}", r.getNo());
                return true;
            }
            User applicant = userMapper.findByName(r.getApplicant());
            if (applicant == null || applicant.getDingUserId() == null || applicant.getDingUserId().isBlank()) {
                throw new IllegalStateException("申请人未绑定钉钉账号");
            }
            String processCode = s.getApprovalProcessCodes().get(r.getType());
            String instanceId = dingTalk.createApprovalInstance(
                    processCode, applicant.getDingUserId(), applicant.getDeptId(), buildForm(r, s));
            r.setDingInstanceId(instanceId);
            r.setDingStatus("RUNNING");
            r.setDingSyncedAt(LocalDateTime.now());
            r.setDingMessage("已提交钉钉审批");
            requestMapper.update(r);
            log.info("ding approval started: {} -> {}", r.getNo(), instanceId);
            return true;
        } catch (Exception e) {
            log.warn("start ding approval failed for {}: {}", r.getNo(), e.getMessage());
            r.setDingMessage(trim("发起钉钉审批失败，已降级为系统内审批：" + e.getMessage()));
            r.setDingSyncedAt(LocalDateTime.now());
            requestMapper.update(r);
            return false;
        }
    }

    /**
     * 设备购入申请：尝试发起钉钉审批，失败降级为系统内审批。
     */
    @Transactional
    public boolean tryStart(PurchaseRequest r) {
        if (r == null || !enabledFor(PURCHASE)) return false;
        RuntimeSettings s = settings.get();
        try {
            if (Boolean.TRUE.equals(s.getApprovalMock())) {
                r.setDingInstanceId("MOCK-" + r.getNo());
                r.setDingStatus("RUNNING");
                r.setDingSyncedAt(LocalDateTime.now());
                r.setDingMessage("本地联调模式（未真实调用钉钉）");
                purchaseMapper.update(r);
                log.info("ding approval mock started: {}", r.getNo());
                return true;
            }
            User applicant = userMapper.findByName(r.getApplicant());
            if (applicant == null || applicant.getDingUserId() == null || applicant.getDingUserId().isBlank()) {
                throw new IllegalStateException("申请人未绑定钉钉账号");
            }
            String processCode = s.getApprovalProcessCodes().get(PURCHASE);
            String instanceId = dingTalk.createApprovalInstance(
                    processCode, applicant.getDingUserId(), applicant.getDeptId(), buildPurchaseForm(r, s));
            r.setDingInstanceId(instanceId);
            r.setDingStatus("RUNNING");
            r.setDingSyncedAt(LocalDateTime.now());
            r.setDingMessage("已提交钉钉审批");
            purchaseMapper.update(r);
            log.info("ding approval started: {} -> {}", r.getNo(), instanceId);
            return true;
        } catch (Exception e) {
            log.warn("start ding approval failed for {}: {}", r.getNo(), e.getMessage());
            r.setDingMessage(trim("发起钉钉审批失败，已降级为系统内审批：" + e.getMessage()));
            r.setDingSyncedAt(LocalDateTime.now());
            purchaseMapper.update(r);
            return false;
        }
    }

    /** 购入申请的表单组装 */
    private List<Map<String, Object>> buildPurchaseForm(PurchaseRequest r, RuntimeSettings s) {
        Map<String, String> mapping = new LinkedHashMap<>();
        if (s.getApprovalMappings() != null) {
            Map<String, String> configured = s.getApprovalMappings().get(PURCHASE);
            if (configured != null) mapping.putAll(configured);
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("no", r.getNo());
        values.put("bizType", TYPE_NAME.get(PURCHASE));
        values.put("itemName", nz(r.getItemName()));
        values.put("category", nz(r.getCategory()));
        values.put("applicant", nz(r.getApplicant()));
        values.put("dept", nz(r.getDept()));
        values.put("reason", nz(r.getReason()));

        List<Map<String, Object>> list = new ArrayList<>();
        for (FormField f : PURCHASE_FIELDS) {
            String value = values.get(f.key());
            if (value == null || value.isBlank()) continue;
            String name = mapping.get(f.key());
            if (name == null || name.isBlank()) name = f.defaultName();
            if ("-".equals(name.trim())) continue;
            list.add(field(name.trim(), value));
        }
        return list;
    }

    /** 按「业务字段映射」组装钉钉表单；未配置用默认控件名，配置为 - 则跳过 */
    private List<Map<String, Object>> buildForm(AssetRequest r, RuntimeSettings s) {
        Map<String, String> mapping = new LinkedHashMap<>();
        if (s.getApprovalMappings() != null) {
            Map<String, String> configured = s.getApprovalMappings().get(r.getType());
            if (configured != null) mapping.putAll(configured);
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("no", r.getNo());
        values.put("bizType", TYPE_NAME.getOrDefault(r.getType(), r.getType()));
        values.put("assetNo", nz(r.getAssetNo()));
        values.put("assetName", nz(r.getAssetName()));
        values.put("assetCategory", nz(r.getAssetCategory()));
        values.put("applicant", nz(r.getApplicant()));
        values.put("dept", nz(r.getDept()));
        values.put("reason", nz(r.getReason()));
        if (r.getExpectReturn() != null) values.put("expectReturn", r.getExpectReturn().toString());
        if (r.getNewAssetNo() != null && !r.getNewAssetNo().isBlank()) values.put("newAssetNo", r.getNewAssetNo());

        List<Map<String, Object>> list = new ArrayList<>();
        for (FormField f : FIELDS) {
            String value = values.get(f.key());
            if (value == null || value.isBlank()) continue;
            String name = mapping.get(f.key());
            if (name == null || name.isBlank()) name = f.defaultName();
            if ("-".equals(name.trim())) continue;
            list.add(field(name.trim(), value));
        }
        return list;
    }

    private Map<String, Object> field(String name, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("value", value == null ? "" : value);
        return m;
    }

    private String nz(String v) { return v == null ? "" : v; }

    private String trim(String s) {
        if (s == null) return null;
        return s.length() > 250 ? s.substring(0, 250) : s;
    }
}
