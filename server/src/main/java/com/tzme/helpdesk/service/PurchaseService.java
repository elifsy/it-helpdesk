package com.tzme.helpdesk.service;

import com.tzme.helpdesk.common.BizException;
import com.tzme.helpdesk.common.CurrentUser;
import com.tzme.helpdesk.domain.Asset;
import com.tzme.helpdesk.domain.PurchaseCatalog;
import com.tzme.helpdesk.domain.PurchaseRequest;
import com.tzme.helpdesk.domain.PurchaseRequestItem;
import com.tzme.helpdesk.mapper.AssetMapper;
import com.tzme.helpdesk.mapper.PurchaseCatalogMapper;
import com.tzme.helpdesk.mapper.PurchaseRequestItemMapper;
import com.tzme.helpdesk.mapper.PurchaseRequestMapper;
import com.tzme.helpdesk.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备购入申请：目录维护 + 申请单流转。
 *
 * 一张申请单可以有多条明细（购物车），每条明细 = 一台设备，各自独立流转：
 *   PENDING 待处理 → 库存够就直接配发（→ 待领取）；不够就填 ERP（→ 已申请采购）→ 到货后配发（→ 待领取）
 *   → IT 确认交付（→ 已交付）。全部明细交付后整单完成。
 * 审批是针对整张申请单的（可走钉钉），通过后才允许 IT 逐项处理。
 */
@Service
@RequiredArgsConstructor
public class PurchaseService {

    private static final Map<String, String> STATUS_NAME = Map.of(
            "PENDING", "待审批", "APPROVED", "已批准待处理",
            "COMPLETED", "已完成", "REJECTED", "已驳回", "CANCELLED", "已撤销");

    private static final Map<String, String> ITEM_STATUS_NAME = Map.of(
            "PENDING", "待处理", "ORDERED", "已申请采购",
            "PENDING_PICKUP", "待领取", "COMPLETED", "已交付");

    /** 一张单最多允许的明细行数与总台数，避免误操作把单子撑爆 */
    private static final int MAX_LINES = 30;
    private static final int MAX_QTY = 50;

    private final PurchaseCatalogMapper catalogMapper;
    private final PurchaseRequestMapper requestMapper;
    private final PurchaseRequestItemMapper itemMapper;
    private final AssetMapper assetMapper;
    private final AssetService assetService;
    private final NotifyService notifyService;
    private final MissingReportGuard missingReports;
    private final NoGen noGen;
    private final AuditService audit;
    private final DingApprovalService dingApproval;

    /* ------------------------------ 目录 ------------------------------ */

    public List<PurchaseCatalog> catalog(boolean onlyEnabled) {
        return catalogMapper.list(onlyEnabled ? 1 : 0);
    }

    @Transactional
    public PurchaseCatalog saveCatalog(PurchaseCatalog input) {
        com.tzme.helpdesk.security.Permissions.require("asset.create");
        if (input.getName() == null || input.getName().isBlank()) throw new BizException("请填写设备名称");
        if (input.getCategory() == null || input.getCategory().isBlank()) throw new BizException("请选择资产分类");
        if (input.getEnabled() == null) input.setEnabled(1);
        if (input.getSort() == null) input.setSort(0);
        if (input.getId() == null) {
            catalogMapper.insert(input);
            audit.record("PURCHASE_CATALOG_ADD", String.valueOf(input.getId()), input.getName());
        } else {
            catalogMapper.update(input);
            audit.record("PURCHASE_CATALOG_EDIT", String.valueOf(input.getId()), input.getName());
        }
        return catalogMapper.findById(input.getId());
    }

    @Transactional
    public void deleteCatalog(Long id) {
        com.tzme.helpdesk.security.Permissions.require("asset.create");
        catalogMapper.delete(id);
        audit.record("PURCHASE_CATALOG_DEL", String.valueOf(id), "删除采购目录项");
    }

    /* ------------------------------ 申请单 ------------------------------ */

    public List<PurchaseRequest> list(String status, Boolean mine) {
        LoginUser u = CurrentUser.require();
        boolean mineFlag = Boolean.TRUE.equals(mine) || u.isEmployeeOnly();
        List<PurchaseRequest> rows = requestMapper.list(status, u.name(), mineFlag);
        if (!com.tzme.helpdesk.security.Roles.isItOrAdmin(u.role())) rows.forEach(row -> row.setPendingReportCount(null));
        for (PurchaseRequest r : rows) attachItems(r);
        return rows;
    }

    public PurchaseRequest detail(Long id) {
        LoginUser user = CurrentUser.require();
        PurchaseRequest request = requireRequest(id);
        if (!user.hasPermission("request.view.all") && !user.hasPermission("request.approve")
                && !request.getApplicant().equals(user.name())) {
            throw new BizException(403, "无权查看这张购入申请");
        }
        return withItems(request);
    }

    private PurchaseRequest requireRequest(Long id) {
        PurchaseRequest r = requestMapper.findById(id);
        if (r == null) throw new BizException(404, "申请单不存在");
        return r;
    }

    private PurchaseRequest withItems(PurchaseRequest request) {
        attachItems(request);
        return request;
    }

    /** 购物车行：目录项 + 台数 */
    public record CartLine(Long catalogId, Integer qty) { }

    @Transactional
    public PurchaseRequest create(List<CartLine> lines, String reason) {
        LoginUser u = CurrentUser.require();
        if (!u.hasPermission("request.create")) throw new BizException(403, "当前账号不能提交申请");
        missingReports.requireCanSubmit(u.id());
        if (reason == null || reason.trim().length() < 4) throw new BizException("请填写购入事由（至少 4 个字）");
        if (reason.trim().length() > 500) throw new BizException("购入事由最多 500 个字");
        if (lines == null || lines.isEmpty()) throw new BizException("请先选择需要购买的设备");

        // 同一型号合并数量；逐项校验目录状态
        Map<Long, Integer> merged = new LinkedHashMap<>();
        int total = 0;
        for (CartLine line : lines) {
            if (line == null || line.catalogId() == null) continue;
            int qty = line.qty() == null ? 1 : line.qty();
            if (qty < 1) throw new BizException("申请数量至少为 1");
            if (qty > MAX_QTY - total) throw new BizException("一张申请单最多申请 " + MAX_QTY + " 台设备");
            total += qty;
            merged.merge(line.catalogId(), qty, Integer::sum);
        }
        if (merged.isEmpty()) throw new BizException("请先选择需要购买的设备");
        if (merged.size() > MAX_LINES) throw new BizException("一张申请单最多 " + MAX_LINES + " 种设备");

        List<PurchaseCatalog> picked = new ArrayList<>();
        StringBuilder summary = new StringBuilder();
        for (Map.Entry<Long, Integer> e : merged.entrySet()) {
            PurchaseCatalog item = catalogMapper.findById(e.getKey());
            if (item == null) throw new BizException(404, "设备不在采购目录中");
            if (item.getEnabled() == null || item.getEnabled() != 1) throw new BizException("「" + item.getName() + "」已下架，不能申请购买");
            picked.add(item);
            if (summary.length() > 0) summary.append("；");
            summary.append(item.getName()).append(" ×").append(e.getValue());
        }

        StringBuilder cats = new StringBuilder();
        for (PurchaseCatalog item : picked) {
            if (cats.indexOf(item.getCategory()) >= 0) continue;
            if (cats.length() > 0) cats.append(" / ");
            cats.append(item.getCategory());
        }

        PurchaseRequest r = new PurchaseRequest();
        r.setNo(noGen.next("PR"));
        r.setCategory(trimCats(cats.toString()));
        r.setApplicant(u.name());
        r.setApplicantId(u.id());
        r.setDept(u.deptName());
        r.setReason(reason.trim());
        r.setStatus("PENDING");
        // item_name 保留为整单摘要（历史列，NOT NULL）
        r.setItemName(trim(summary.toString()));
        requestMapper.insert(r);

        for (PurchaseCatalog item : picked) {
            int qty = merged.get(item.getId());
            for (int i = 0; i < qty; i++) {
                PurchaseRequestItem line = new PurchaseRequestItem();
                line.setRequestId(r.getId());
                line.setCatalogId(item.getId());
                line.setItemName(item.getName());
                line.setCategory(item.getCategory());
                line.setSpec(item.getSpec());
                line.setStatus("PENDING");
                itemMapper.insert(line);
            }
        }

        if (dingApproval.tryStart(r)) {
            notifyService.send(u.name(), "购入申请已提交",
                    r.getNo() + " 共 " + total + " 台设备已提交，已进入钉钉审批，结果会自动同步。", "PURCHASE", r.getNo());
        } else {
            notifyService.notifyPermission("request.approve", "有新的设备购入申请",
                    r.getNo() + " 共 " + total + " 台（" + u.name() + "）待审批", "PURCHASE", r.getNo());
            notifyService.send(u.name(), "购入申请已提交",
                    r.getNo() + " 共 " + total + " 台设备已提交，等待审批。", "PURCHASE", r.getNo());
        }
        return detail(r.getId());
    }

    @Transactional
    public PurchaseRequest approve(Long id, String remark) {
        requireApprove();
        PurchaseRequest r = requestMapper.findByIdForUpdate(id);
        if (r == null) throw new BizException(404, "申请单不存在");
        if (r.getDingInstanceId() != null && !r.getDingInstanceId().isBlank()) {
            throw new BizException(409, "该申请在钉钉审批中（" + r.getDingInstanceId() + "），请到钉钉里处理");
        }
        return approveAs(r, CurrentUser.require().name(), remark);
    }

    /** 审批通过的实际执行：系统内审批与钉钉轮询回写共用 */
    @Transactional
    public PurchaseRequest approveAs(PurchaseRequest r, String operator, String remark) {
        if (r == null) throw new BizException(404, "申请单不存在");
        if (!"PENDING".equals(r.getStatus())) throw new BizException("该申请已处理");
        r.setStatus("APPROVED");
        r.setApprover(operator);
        r.setHandledAt(LocalDateTime.now());
        if (remark != null && !remark.isBlank()) r.setRemark(remark.trim());
        requestMapper.update(r);
        int total = itemMapper.countByRequest(r.getId());
        notifyService.send(r.getApplicant(), "购入申请已批准",
                r.getNo() + " 已批准（共 " + total + " 台），IT 会逐项安排库存配发或采购。", "PURCHASE", r.getNo());
        audit.record("PURCHASE_APPROVE", r.getNo(), "明细 " + total + " 台；操作人=" + operator);
        return withItems(requireRequest(r.getId()));
    }

    @Transactional
    public PurchaseRequest reject(Long id, String reason) {
        requireApprove();
        PurchaseRequest r = requestMapper.findByIdForUpdate(id);
        if (r == null) throw new BizException(404, "申请单不存在");
        if (r.getDingInstanceId() != null && !r.getDingInstanceId().isBlank()) {
            throw new BizException(409, "该申请在钉钉审批中（" + r.getDingInstanceId() + "），请到钉钉里处理");
        }
        return rejectAs(r, CurrentUser.require().name(), reason);
    }

    /** 驳回的实际执行：系统内审批与钉钉轮询回写共用 */
    @Transactional
    public PurchaseRequest rejectAs(PurchaseRequest r, String operator, String reason) {
        if (r == null) throw new BizException(404, "申请单不存在");
        if (!"PENDING".equals(r.getStatus())) throw new BizException("该申请已处理");
        r.setStatus("REJECTED");
        r.setApprover(operator);
        r.setHandledAt(LocalDateTime.now());
        r.setRemark(reason == null || reason.isBlank() ? "审批未通过" : reason.trim());
        requestMapper.update(r);
        notifyService.send(r.getApplicant(), "购入申请被驳回",
                r.getNo() + " 驳回原因：" + r.getRemark(), "PURCHASE", r.getNo());
        audit.record("PURCHASE_REJECT", r.getNo(), r.getRemark() + "；操作人=" + operator);
        return withItems(requireRequest(r.getId()));
    }

    /* --------------------------- 明细逐项操作 --------------------------- */

    /** 库存能满足：直接给这条明细挑一台设备 → 待领取 */
    @Transactional
    public PurchaseRequest assignStock(Long requestId, Long itemId, String assetNo) {
        requireApprove();
        PurchaseRequest r = lockApprovedRequest(requestId);
        PurchaseRequestItem item = lockItem(r, itemId);
        if ("COMPLETED".equals(item.getStatus())) throw new BizException("该设备已交付");
        if ("PENDING_PICKUP".equals(item.getStatus())) throw new BizException("该设备已指定，请直接确认交付");
        if (assetNo == null || assetNo.isBlank()) throw new BizException("请选择一台设备");
        Asset a = checkFreeAsset(assetNo);
        if (assetNo.equals(item.getAssetNo())) throw new BizException("该设备已经指定给这条明细");
        PurchaseRequestItem other = itemMapper.findPendingByAsset(assetNo);
        if (other != null && !other.getId().equals(item.getId()))
            throw new BizException(409, "该设备已被申请单 " + r.getNo() + " 的另一条明细占用，请换一台");

        item.setAssetNo(a.getNo());
        item.setStatus("PENDING_PICKUP");
        itemMapper.update(item);
        notifyService.send(r.getApplicant(), "设备已就绪，待领取",
                r.getNo() + " 的「" + item.getItemName() + "」已配好（" + a.getNo() + "），请到 IT 服务台领取。", "PURCHASE", r.getNo());
        audit.record("PURCHASE_ITEM_ASSIGN", r.getNo(),
                item.getItemName() + " → " + a.getNo() + "（库存配发）；操作人=" + CurrentUser.require().name());
        return detail(requestId);
    }

    /** 库存不足：这条明细转为采购，填 ERP 单号 */
    @Transactional
    public PurchaseRequest markOrdered(Long requestId, Long itemId, String erpNo) {
        requireApprove();
        if (erpNo == null || erpNo.isBlank()) throw new BizException("请填写 ERP 采购单号");
        PurchaseRequest r = lockApprovedRequest(requestId);
        PurchaseRequestItem item = lockItem(r, itemId);
        if (!"PENDING".equals(item.getStatus()))
            throw new BizException("只有「待处理」的设备才能转为采购");
        item.setErpNo(erpNo.trim());
        item.setOrderedAt(LocalDateTime.now());
        item.setStatus("ORDERED");
        itemMapper.update(item);
        notifyService.send(r.getApplicant(), "已进入采购流程",
                r.getNo() + " 的「" + item.getItemName() + "」已提交采购（ERP：" + item.getErpNo() + "）。", "PURCHASE", r.getNo());
        audit.record("PURCHASE_ITEM_ORDER", r.getNo(), item.getItemName() + "；ERP=" + item.getErpNo());
        return detail(requestId);
    }

    /** 到货后（或库存）指定设备 → 待领取 */
    @Transactional
    public PurchaseRequest assignArrival(Long requestId, Long itemId, String assetNo) {
        // 与库存配发同一动作，单独暴露是为了语义清晰
        return assignStock(requestId, itemId, assetNo);
    }

    /** 逐台确认交付：设备绑定到申请人，全部交付后整单完成 */
    @Transactional
    public PurchaseRequest deliver(Long requestId, Long itemId) {
        requireApprove();
        PurchaseRequest r = lockApprovedRequest(requestId);
        PurchaseRequestItem item = lockItem(r, itemId);
        if (!"PENDING_PICKUP".equals(item.getStatus()))
            throw new BizException("该设备不在待领取状态");
        Asset a = assetMapper.findByNoForUpdate(item.getAssetNo());
        if (a == null) throw new BizException(404, "资产不存在：" + item.getAssetNo());
        if (!"闲置".equals(a.getStatus()) && !"待入库".equals(a.getStatus()))
            throw new BizException("该设备已被占用，无法交付：" + a.getStatus());

        String operator = CurrentUser.require().name();
        a.setUserName(r.getApplicant());
        a.setBorrower("");
        a.setStatus("在用");
        assetMapper.update(a);
        assetService.addHistory(a.getNo(), "领用",
                "购入申请 " + r.getNo() + " 采购入库后配发给 " + r.getApplicant(), operator);

        item.setStatus("COMPLETED");
        item.setDeliveredBy(operator);
        item.setDeliveredAt(LocalDateTime.now());
        itemMapper.update(item);

        int total = itemMapper.countByRequest(requestId);
        int done = itemMapper.countDone(requestId);
        boolean allDone = done >= total;
        if (allDone) {
            r.setStatus("COMPLETED");
            r.setHandledAt(LocalDateTime.now());
            requestMapper.update(r);
            notifyService.send(r.getApplicant(), "购入申请已完成",
                    r.getNo() + " 全部 " + total + " 台设备已交付完成。", "PURCHASE", r.getNo());
        } else {
            notifyService.send(r.getApplicant(), "设备已领取",
                    r.getNo() + " 的「" + item.getItemName() + "」已交付（" + a.getNo() + "），剩余 "
                            + (total - done) + " 台。", "PURCHASE", r.getNo());
        }
        audit.record("PURCHASE_ITEM_DELIVER", r.getNo(),
                item.getItemName() + " → " + a.getNo() + "；进度 " + done + "/" + total);
        return detail(requestId);
    }

    @Transactional
    public PurchaseRequest cancel(Long id) {
        LoginUser u = CurrentUser.require();
        PurchaseRequest r = requestMapper.findByIdForUpdate(id);
        if (r == null) throw new BizException(404, "申请单不存在");
        if (!r.getApplicant().equals(u.name())) throw new BizException(403, "只能撤销自己的申请");
        if (!"PENDING".equals(r.getStatus())) throw new BizException("已进入审批后续流程，不能撤销");
        r.setStatus("CANCELLED");
        r.setHandledAt(LocalDateTime.now());
        r.setRemark("申请人主动撤销");
        requestMapper.update(r);
        return detail(id);
    }

    public long countPending() {
        return requestMapper.countPending();
    }

    public String statusName(String status) {
        return STATUS_NAME.getOrDefault(status, status);
    }

    public Map<String, String> itemStatusNames() {
        return ITEM_STATUS_NAME;
    }

    /* ------------------------------ 内部 ------------------------------ */

    private PurchaseRequest lockApprovedRequest(Long id) {
        PurchaseRequest r = requestMapper.findByIdForUpdate(id);
        if (r == null) throw new BizException(404, "申请单不存在");
        if (!"APPROVED".equals(r.getStatus())) throw new BizException("只有审批通过、尚未完成的申请才能逐项处理");
        return r;
    }

    private PurchaseRequestItem lockItem(PurchaseRequest r, Long itemId) {
        PurchaseRequestItem item = itemMapper.findByIdForUpdate(itemId);
        if (item == null || !item.getRequestId().equals(r.getId())) throw new BizException(404, "申请明细不存在");
        return item;
    }

    private Asset checkFreeAsset(String assetNo) {
        Asset a = assetMapper.findByNoForUpdate(assetNo);
        if (a == null) throw new BizException(404, "资产不存在：" + assetNo);
        boolean idle = "闲置".equals(a.getStatus()) || "待入库".equals(a.getStatus());
        boolean free = (a.getUserName() == null || a.getUserName().isBlank())
                && (a.getBorrower() == null || a.getBorrower().isBlank());
        if (!idle || !free) throw new BizException("只能选择闲置或待入库、且无人使用的设备");
        return a;
    }

    private void attachItems(PurchaseRequest r) {
        if (r == null) return;
        List<PurchaseRequestItem> items = itemMapper.listByRequest(r.getId());
        r.setItems(items);
        r.setItemCount(items.size());
        int done = 0;
        for (PurchaseRequestItem item : items) if ("COMPLETED".equals(item.getStatus())) done++;
        r.setDoneCount(done);
        if (items.isEmpty()) {
            r.setItemSummary(r.getItemName());
            return;
        }
        String first = items.get(0).getItemName();
        r.setItemSummary(items.size() == 1
                ? first
                : first + " 等 " + distinctNames(items) + " 种 / 共 " + items.size() + " 台");
    }

    private int distinctNames(List<PurchaseRequestItem> items) {
        return (int) items.stream().map(PurchaseRequestItem::getItemName).distinct().count();
    }

    private String trimCats(String s) {
        if (s == null) return null;
        return s.length() > 64 ? s.substring(0, 61) + "…" : s;
    }

    private String trim(String s) {
        if (s == null) return null;
        return s.length() > 120 ? s.substring(0, 117) + "…" : s;
    }

    private void requireApprove() {
        if (!CurrentUser.require().hasPermission("request.approve")) {
            throw new BizException(403, "只有 IT 或管理员可以操作购入申请");
        }
    }
}
