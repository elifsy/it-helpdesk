package com.tzme.helpdesk.controller;

import com.tzme.helpdesk.common.ApiResponse;
import com.tzme.helpdesk.common.BizException;
import com.tzme.helpdesk.domain.AssetRequest;
import com.tzme.helpdesk.domain.PurchaseRequest;
import com.tzme.helpdesk.mapper.AssetRequestMapper;
import com.tzme.helpdesk.mapper.PurchaseRequestMapper;
import com.tzme.helpdesk.service.DingApprovalService;
import com.tzme.helpdesk.service.DingApprovalSyncService;
import com.tzme.helpdesk.service.RuntimeSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 钉钉审批运维接口：立即同步一次、查看状态、本地联调时模拟审批结果。
 */
@RestController
@RequestMapping("/admin/ding-approval")
@RequiredArgsConstructor
public class DingApprovalController {

    private final DingApprovalSyncService syncService;
    private final DingApprovalService dingApproval;
    private final RuntimeSettingsService settings;
    private final AssetRequestMapper requestMapper;
    private final PurchaseRequestMapper purchaseMapper;
    private final com.tzme.helpdesk.service.DingTalkService dingTalk;

    /** 可映射的系统字段清单（供配置页渲染映射表） */
    @GetMapping("/fields")
    @com.tzme.helpdesk.security.RequiresPermission({"settings.manage"})
    public ApiResponse<java.util.List<DingApprovalService.FormField>> fields(
            @RequestParam(required = false, defaultValue = "") String type) {
        return ApiResponse.ok(dingApproval.fieldCatalog(type));
    }

    /** 按钉钉审批模板名称查询 processCode，供配置页一键填入 */
    @GetMapping("/lookup")
    @com.tzme.helpdesk.security.RequiresPermission({"settings.manage"})
    public ApiResponse<Map<String, Object>> lookup(@RequestParam String name) {
        Map<String, Object> r = dingTalk.findProcessByName(name);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", r.get("name"));
        m.put("processCode", r.get("process_code"));
        m.put("url", r.get("url"));
        return ApiResponse.ok(m);
    }

    @GetMapping("/status")
    @com.tzme.helpdesk.security.RequiresPermission({"request.approve"})
    public ApiResponse<Map<String, Object>> status() {
        var s = settings.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", s.getApprovalEnabled());
        m.put("mock", s.getApprovalMock());
        m.put("pollMinutes", s.getApprovalPollMinutes());
        m.put("processCodes", s.getApprovalProcessCodes());
        m.put("pending", requestMapper.listDingPending().size() + purchaseMapper.listDingPending().size());
        return ApiResponse.ok(m);
    }

    @PostMapping("/sync")
    @com.tzme.helpdesk.security.RequiresPermission({"request.approve"})
    public ApiResponse<Map<String, Object>> syncNow() {
        int handled = syncService.syncAll();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("handled", handled);
        m.put("at", LocalDateTime.now().toString());
        return ApiResponse.ok(m);
    }

    /** 本地联调：把某张单的钉钉状态改成 COMPLETED / TERMINATED，下一次轮询即按结果执行 */
    @PostMapping("/mock/{no}")
    @com.tzme.helpdesk.security.RequiresPermission({"request.approve"})
    public ApiResponse<Object> mock(@PathVariable String no,
                                    @RequestParam(defaultValue = "COMPLETED") String status) {
        if (!dingApproval.mockMode()) {
            throw new BizException(409, "当前不是本地联调模式，不能模拟钉钉审批结果");
        }
        String target = "TERMINATED".equalsIgnoreCase(status) ? "TERMINATED" : "COMPLETED";
        // 采购单与资产申请单号前缀不同，先按 PR- 判断，再回退到资产申请
        if (no != null && no.startsWith("PR-")) {
            PurchaseRequest pr = purchaseMapper.findByNo(no);
            if (pr == null) throw new BizException(404, "申请单不存在");
            if (!"PENDING".equals(pr.getStatus())) throw new BizException("该申请已处理");
            pr.setDingStatus(target);
            pr.setDingSyncedAt(LocalDateTime.now());
            pr.setDingMessage("联调：模拟钉钉结果为 " + target);
            purchaseMapper.update(pr);
            return ApiResponse.ok(purchaseMapper.findById(pr.getId()));
        }
        AssetRequest r = requestMapper.findByNo(no);
        if (r == null) throw new BizException(404, "申请单不存在");
        if (!"PENDING".equals(r.getStatus())) throw new BizException("该申请已处理");
        r.setDingStatus(target);
        r.setDingSyncedAt(LocalDateTime.now());
        r.setDingMessage("联调：模拟钉钉结果为 " + target);
        requestMapper.update(r);
        return ApiResponse.ok(requestMapper.findById(r.getId()));
    }
}
