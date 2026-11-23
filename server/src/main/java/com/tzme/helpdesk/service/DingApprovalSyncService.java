package com.tzme.helpdesk.service;

import com.tzme.helpdesk.domain.AssetRequest;
import com.tzme.helpdesk.domain.PurchaseRequest;
import com.tzme.helpdesk.dto.RuntimeSettings;
import com.tzme.helpdesk.mapper.AssetRequestMapper;
import com.tzme.helpdesk.mapper.ManagementMapper;
import com.tzme.helpdesk.mapper.PurchaseRequestMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 钉钉审批结果同步（轮询）。
 * 只处理「已发起钉钉审批且仍在 PENDING」的申请：
 *  - COMPLETED + agree → 按通过执行资产动作（绑定使用人 / 借出 / 回库 / 换机）
 *  - COMPLETED + refuse 或 TERMINATED → 按驳回处理并通知申请人
 * 同步是幂等的：申请状态一旦离开 PENDING 就不再进入队列。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DingApprovalSyncService {

    private final AssetRequestMapper requestMapper;
    private final AssetRequestService assetRequestService;
    private final DingTalkService dingTalk;
    private final DingApprovalService dingApproval;
    private final RuntimeSettingsService settings;
    private final ManagementMapper management;
    private final PurchaseRequestMapper purchaseMapper;
    private final PurchaseService purchaseService;

    /** 扫描并同步全部在途钉钉审批，返回本次处理的单数 */
    public int syncAll() {
        RuntimeSettings s = settings.get();
        if (!Boolean.TRUE.equals(s.getApprovalEnabled())) return 0;
        int handled = 0;
        int failed = 0;
        for (AssetRequest r : requestMapper.listDingPending()) {
            try {
                if (syncOne(r.getId(), s)) handled++;
            } catch (Exception e) {
                failed++;
                log.warn("sync ding approval {} failed: {}", r.getNo(), e.getMessage());
            }
        }
        for (PurchaseRequest r : purchaseMapper.listDingPending()) {
            try {
                if (syncOnePurchase(r.getId(), s)) handled++;
            } catch (Exception e) {
                failed++;
                log.warn("sync ding approval {} failed: {}", r.getNo(), e.getMessage());
            }
        }
        management.jobResult("ding-approval",
                failed > 0 ? "FAILED" : "SUCCESS",
                "同步完成：处理 " + handled + " 笔" + (failed > 0 ? "，失败 " + failed + " 笔" : ""));
        return handled;
    }

    /** 同步单笔，返回是否产生了状态推进 */
    @Transactional
    public boolean syncOne(Long id, RuntimeSettings s) {
        AssetRequest snapshot = requestMapper.findById(id);
        if (snapshot == null || !"PENDING".equals(snapshot.getStatus())) return false;
        String instanceId = snapshot.getDingInstanceId();
        if (instanceId == null || instanceId.isBlank()) return false;

        String status;
        String result = null;
        if (Boolean.TRUE.equals(s.getApprovalMock())) {
            // 本地联调：状态由管理端“模拟审批结果”接口写入 ding_status
            status = snapshot.getDingStatus();
            result = "COMPLETED".equals(status) ? "agree" : null;
        } else {
            Map<String, Object> inst = dingTalk.getApprovalInstance(instanceId);
            if (inst == null) {
                touch(snapshot, null, "查询钉钉审批失败，稍后重试");
                return false;
            }
            status = str(inst.get("status"));
            result = str(inst.get("result"));
        }
        if (status == null || "RUNNING".equals(status)) {
            // 仍在审批中：只在首次同步时留个痕迹，避免每分钟写库
            if (snapshot.getDingSyncedAt() == null) touch(snapshot, "RUNNING", "钉钉审批中");
            return false;
        }

        AssetRequest r = requestMapper.findByIdForUpdate(id);
        if (r == null || !"PENDING".equals(r.getStatus())) return false;

        boolean approved = "COMPLETED".equals(status) && !"refuse".equalsIgnoreCase(result);
        r.setDingStatus(status);
        r.setDingSyncedAt(LocalDateTime.now());
        if (approved) {
            r.setDingMessage("钉钉审批通过");
            requestMapper.update(r);
            assetRequestService.approveAs(r, "钉钉审批回写", null);
            log.info("ding approval approved: {} ({})", r.getNo(), instanceId);
        } else {
            r.setDingMessage("COMPLETED".equals(status) ? "钉钉审批驳回" : "钉钉审批已终止");
            requestMapper.update(r);
            assetRequestService.rejectAs(r, "钉钉审批回写",
                    "COMPLETED".equals(status) ? "钉钉审批未通过" : "钉钉审批已终止/撤销");
            log.info("ding approval rejected: {} ({})", r.getNo(), instanceId);
        }
        return true;
    }

    /** 同步单笔购入申请，返回是否产生了状态推进 */
    @Transactional
    public boolean syncOnePurchase(Long id, RuntimeSettings s) {
        PurchaseRequest snapshot = purchaseMapper.findById(id);
        if (snapshot == null || !"PENDING".equals(snapshot.getStatus())) return false;
        String instanceId = snapshot.getDingInstanceId();
        if (instanceId == null || instanceId.isBlank()) return false;

        String status;
        String result = null;
        if (Boolean.TRUE.equals(s.getApprovalMock())) {
            status = snapshot.getDingStatus();
            result = "COMPLETED".equals(status) ? "agree" : null;
        } else {
            Map<String, Object> inst = dingTalk.getApprovalInstance(instanceId);
            if (inst == null) {
                touchPurchase(snapshot, null, "查询钉钉审批失败，稍后重试");
                return false;
            }
            status = str(inst.get("status"));
            result = str(inst.get("result"));
        }
        if (status == null || "RUNNING".equals(status)) {
            if (snapshot.getDingSyncedAt() == null) touchPurchase(snapshot, "RUNNING", "钉钉审批中");
            return false;
        }

        PurchaseRequest r = purchaseMapper.findByIdForUpdate(id);
        if (r == null || !"PENDING".equals(r.getStatus())) return false;

        boolean approved = "COMPLETED".equals(status) && !"refuse".equalsIgnoreCase(result);
        r.setDingStatus(status);
        r.setDingSyncedAt(LocalDateTime.now());
        if (approved) {
            r.setDingMessage("钉钉审批通过");
            purchaseMapper.update(r);
            purchaseService.approveAs(r, "钉钉审批回写", null);
            log.info("ding approval approved: {} ({})", r.getNo(), instanceId);
        } else {
            r.setDingMessage("COMPLETED".equals(status) ? "钉钉审批驳回" : "钉钉审批已终止");
            purchaseMapper.update(r);
            purchaseService.rejectAs(r, "钉钉审批回写",
                    "COMPLETED".equals(status) ? "钉钉审批未通过" : "钉钉审批已终止/撤销");
            log.info("ding approval rejected: {} ({})", r.getNo(), instanceId);
        }
        return true;
    }

    private void touchPurchase(PurchaseRequest r, String status, String message) {
        if (status != null) r.setDingStatus(status);
        r.setDingSyncedAt(LocalDateTime.now());
        r.setDingMessage(message == null ? r.getDingMessage() : message);
        purchaseMapper.update(r);
    }

    private void touch(AssetRequest r, String status, String message) {
        if (status != null) r.setDingStatus(status);
        r.setDingSyncedAt(LocalDateTime.now());
        r.setDingMessage(message == null ? r.getDingMessage() : message);
        requestMapper.update(r);
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
