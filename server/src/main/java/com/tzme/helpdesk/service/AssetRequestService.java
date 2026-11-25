package com.tzme.helpdesk.service;

import com.tzme.helpdesk.common.BizException;
import com.tzme.helpdesk.common.CurrentUser;
import com.tzme.helpdesk.domain.Asset;
import com.tzme.helpdesk.domain.AssetRequest;
import com.tzme.helpdesk.dto.ApproveReq;
import com.tzme.helpdesk.dto.AssetRequestCreateReq;
import com.tzme.helpdesk.mapper.AssetMapper;
import com.tzme.helpdesk.mapper.AssetRequestMapper;
import com.tzme.helpdesk.mapper.TicketMapper;
import com.tzme.helpdesk.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AssetRequestService {

    private static final Set<String> TYPES = Set.of("RECEIVE", "BORROW", "REPLACE", "RETURN");
    private static final Map<String, String> TYPE_NAME = Map.of(
            "RECEIVE", "资产领用", "BORROW", "资产借用", "REPLACE", "资产更换", "RETURN", "归还设备");

    private final AssetRequestMapper requestMapper;
    private final AssetMapper assetMapper;
    private final TicketMapper ticketMapper;
    private final AssetService assetService;
    private final NotifyService notifyService;
    private final MissingReportGuard missingReports;
    private final IncomingNotificationService incomingNotifications;
    private final NoGen noGen;
    private final AuditService audit;
    private final DingApprovalService dingApproval;
    private final RuntimeSettingsService settings;

    public List<AssetRequest> list(String status, Boolean mine, String type, String no) {
        LoginUser u = CurrentUser.require();
        boolean mineFlag = Boolean.TRUE.equals(mine) || u.isEmployeeOnly();
        var rows = requestMapper.list(status, mineFlag ? u.name() : null, mineFlag ? u.id() : null, type, no);
        if (!com.tzme.helpdesk.security.Roles.isItOrAdmin(u.role())) rows.forEach(row -> row.setPendingReportCount(null));
        return rows;
    }

    @Transactional
    public AssetRequest create(AssetRequestCreateReq req) {
        LoginUser u = CurrentUser.require();
        missingReports.requireCanSubmit(u.id());
        String type = req.getType() == null ? "" : req.getType().toUpperCase();
        if (!TYPES.contains(type)) {
            throw new BizException("不支持的申请类型");
        }
        Asset a = assetMapper.findByNoForUpdate(req.getAssetNo());
        if (a == null) throw new BizException(404, "资产不存在");
        if(req.getCategory()!=null && !req.getCategory().equals(a.getCategory())) throw new BizException(409,"设备分类已变化，请重新选择设备");
        assetService.assertAllowed(a, type, u.id());
        assertNotReserved(a.getNo());

        // 同一人对同一设备只允许一条待审批申请，避免重复提交
        AssetRequest pending = requestMapper.findPendingByApplicantAndAsset(u.name(), req.getAssetNo());
        if (pending != null)
            throw new BizException(409, "该设备已有一条待审批的「" + TYPE_NAME.getOrDefault(pending.getType(), pending.getType())
                    + "」申请 " + pending.getNo() + "，请等待审批结果，勿重复提交。");

        if ("BORROW".equals(type)) {
            if (req.getExpectReturn() == null) {
                throw new BizException("借用必须约定归还日期");
            }
            if (req.getExpectReturn().isBefore(LocalDate.now())) {
                throw new BizException("归还日期不能早于今天");
            }
        }
        // 更换：允许提交时预选替换设备（钉钉审批下必需，因为审批端无法选台账）
        String presetNewNo = req.getNewAssetNo() == null ? null : req.getNewAssetNo().trim();
        if (presetNewNo != null && presetNewNo.isEmpty()) presetNewNo = null;
        if (presetNewNo != null) {
            if (!"REPLACE".equals(type)) throw new BizException("只有更换申请可以预选替换设备");
            if (presetNewNo.equals(req.getAssetNo())) throw new BizException("替换设备不能是原设备");
            Asset spare = assetMapper.findByNo(presetNewNo);
            if (spare == null) throw new BizException(404, "替换设备不存在");
            assertNotReserved(spare.getNo());
            if (!"闲置".equals(spare.getStatus())
                    || spare.hasUser() || spare.hasBorrower())
                throw new BizException("替换设备必须处于闲置状态");
        }

        AssetRequest r = new AssetRequest();
        r.setNo(noGen.next("AS"));
        r.setType(type);
        r.setAssetNo(req.getAssetNo());
        r.setApplicant(u.name());
        r.setApplicantId(u.id());
        r.setDept(u.deptName());
        r.setReason(req.getReason());
        r.setExpectReturn(req.getExpectReturn());
        r.setNewAssetNo(presetNewNo);
        requestMapper.insert(r);

        r.setAssetName(a.getName());
        r.setAssetCategory(a.getCategory());
        boolean dingStarted = dingApproval.tryStart(r);
        incomingNotifications.newAssetRequest(type, TYPE_NAME.get(type), r.getNo(), a.getName(), u.name(), dingStarted);
        if (dingStarted) {
            notifyService.send(u.id(), u.name(), "申请已提交",
                    r.getNo() + " " + TYPE_NAME.get(type) + " 已提交，已进入钉钉审批（" + r.getDingInstanceId()
                            + "），审批结果会自动同步。", "ASSET", r.getNo());
        } else {
            notifyService.send(u.id(), u.name(), "申请已提交",
                    r.getNo() + " " + TYPE_NAME.get(type) + " 已提交，等待审批。", "ASSET", r.getNo());
        }
        return requestMapper.findById(r.getId());
    }

    @Transactional
    public AssetRequest approve(Long id, ApproveReq req) {
        LoginUser u = CurrentUser.require();
        if (!(u.hasPermission("request.approve"))) {
            throw new BizException(403, "只有资产管理员可以审批");
        }
        AssetRequest r = requestMapper.findByIdForUpdate(id);
        if (r == null) {
            throw new BizException(404, "申请单不存在");
        }
        if (r.getDingInstanceId() != null && !r.getDingInstanceId().isBlank()) {
            throw new BizException(409, "该申请在钉钉审批中（" + r.getDingInstanceId() + "），请到钉钉里处理");
        }
        return approveAs(r, u.name(), req);
    }

    /** 审批通过的实际执行：系统内审批与钉钉轮询回写共用，operator 为实际操作人 */
    @Transactional
    public AssetRequest approveAs(AssetRequest r, String operator, ApproveReq req) {
        if (r == null) {
            throw new BizException(404, "申请单不存在");
        }
        if (!"PENDING".equals(r.getStatus())) {
            throw new BizException("该申请已处理");
        }
        String newNo = req != null && req.getNewAssetNo() != null && !req.getNewAssetNo().isBlank()
                ? req.getNewAssetNo() : r.getNewAssetNo();
        if ("REPLACE".equals(r.getType()) && (newNo == null || newNo.isBlank() || newNo.equals(r.getAssetNo()))) {
            throw new BizException("更换审批必须指定另一台闲置设备");
        }
        java.util.Map<String, Asset> locked = lockAssets(r, newNo);
        Asset a = locked.get(r.getAssetNo());
        r.setApplicantId(assetService.resolveApplicant(r.getApplicantId(), r.getApplicant()).getId());
        assetService.assertAllowed(a, r.getType(), r.getApplicantId());
        if ("BORROW".equals(r.getType()) && (r.getExpectReturn() == null || r.getExpectReturn().isBefore(LocalDate.now()))) {
            throw new BizException("约定归还日期已过期，请重新申请");
        }

        Asset na = "REPLACE".equals(r.getType()) ? locked.get(newNo) : null;
        if (na != null) checkNewAsset(r, na, req);

        // 该分类配置了线下交接：审批通过只标记待领取/待归还，等 IT 确认后再改资产
        if (handoverRequired(r)) {
            if (na != null) r.setNewAssetNo(na.getNo());
            r.setStatus("APPROVED");
            r.setHandoverStatus(("RETURN".equals(r.getType()) || "REPLACE".equals(r.getType())) ? "RETURN" : "PICKUP");
            r.setApprover(operator);
            r.setHandledAt(LocalDateTime.now());
            if (req != null && req.getRemark() != null && !req.getRemark().isBlank()) r.setRemark(req.getRemark());
            requestMapper.update(r);
            notifyHandoverNeeded(r);
            audit.record("ASSET_APPROVE", r.getNo(), r.getType() + "；资产=" + r.getAssetNo()
                    + "；审批人=" + operator + "；进入待交接 " + r.getHandoverStatus());
            return requestMapper.findById(r.getId());
        }

        applyStage(r, a, na, operator);
        complete(r, operator, req);
        return requestMapper.findById(r.getId());
    }

    /**
     * IT/管理员确认线下交接。
     * 待归还 → 归还完成（更换则转入待领取）；待领取 → 设备交付给申请人，整单完成。
     */
    @Transactional
    public AssetRequest confirmHandover(Long id) {
        LoginUser u = CurrentUser.require();
        if (!u.hasPermission("request.approve")) {
            throw new BizException(403, "只有 IT 或管理员可以确认交接");
        }
        AssetRequest r = requestMapper.findByIdForUpdate(id);
        if (r == null) throw new BizException(404, "申请单不存在");
        if (!"APPROVED".equals(r.getStatus()) || r.getHandoverStatus() == null || r.getHandoverStatus().isBlank()) {
            throw new BizException("该申请不在待交接状态");
        }
        r.setApplicantId(assetService.resolveApplicant(r.getApplicantId(), r.getApplicant()).getId());
        if ("RETURN".equals(r.getHandoverStatus())) {
            Asset a = lockOne(r.getAssetNo());
            assetService.assertAllowed(a, r.getType(), r.getApplicantId());
            if ("REPLACE".equals(r.getType())) {
                doReplaceRecycle(a, r, u.name());
                r.setHandoverStatus("PICKUP");
                r.setHandoverBy(u.name());
                r.setHandoverAt(LocalDateTime.now());
                requestMapper.update(r);
                notifyService.send(r.getApplicantId(), r.getApplicant(), "旧设备已回收，待领取新设备",
                        r.getNo() + " 原设备 " + a.getNo() + " 已回库，请到 IT 服务台领取新设备 "
                                + (r.getNewAssetNo() == null ? "" : r.getNewAssetNo()) + "。", "ASSET", r.getNo());
                audit.record("ASSET_HANDOVER", r.getNo(), "回收旧机 " + a.getNo() + "，转入待领取，操作人=" + u.name());
                return requestMapper.findById(id);
            }
            doReturn(a, r, u.name());
        } else {
            if ("REPLACE".equals(r.getType())) {
                Asset na = lockOne(r.getNewAssetNo());
                checkNewAsset(r, na, null);
                doReplaceAssign(na, r, u.name());
            } else if ("RECEIVE".equals(r.getType())) {
                Asset a = lockOne(r.getAssetNo());
                assetService.assertAllowed(a, r.getType(), r.getApplicantId());
                doReceive(a, r, u.name());
            } else if ("BORROW".equals(r.getType())) {
                Asset a = lockOne(r.getAssetNo());
                assetService.assertAllowed(a, r.getType(), r.getApplicantId());
                doBorrow(a, r, u.name());
            } else {
                throw new BizException("该申请不需要领取确认");
            }
        }
        r.setHandoverBy(u.name());
        r.setHandoverAt(LocalDateTime.now());
        complete(r, u.name(), null);
        audit.record("ASSET_HANDOVER", r.getNo(), "线下交接完成，操作人=" + u.name());
        return requestMapper.findById(id);
    }

    /** 该业务 + 资产分类是否配置了线下交接 */
    private boolean handoverRequired(AssetRequest r) {
        var s = settings.get();
        java.util.Map<String, java.util.List<String>> cfg = s.getHandoverCategories();
        if (cfg == null) return false;
        java.util.List<String> cats = cfg.get(r.getType());
        if (cats == null || cats.isEmpty()) return false;
        String category = r.getAssetCategory();
        if (category == null || category.isBlank()) {
            Asset a = assetMapper.findByNo(r.getAssetNo());
            category = a == null ? null : a.getCategory();
        }
        return category != null && cats.contains(category);
    }

    /** 设备已被别人（已批准、待领取）占用时不允许再次申请 */
    private void assertNotReserved(String assetNo) {
        AssetRequest holding = requestMapper.findReservedByAsset(assetNo);
        if (holding != null) {
            throw new BizException(409, "该设备已被 " + holding.getApplicant() + " 的申请 " + holding.getNo()
                    + " 占用（待领取），请选择其他设备或等 IT 完成交接");
        }
    }

    private java.util.Map<String, Asset> lockAssets(AssetRequest r, String newNo) {
        // 两台设备按固定顺序加锁，避免交叉换机时锁顺序相反。
        java.util.Map<String, Asset> locked = new java.util.HashMap<>();
        java.util.List<String> numbers = new java.util.ArrayList<>(java.util.List.of(r.getAssetNo()));
        if ("REPLACE".equals(r.getType()) && newNo != null && !newNo.isBlank()) numbers.add(newNo);
        numbers.sort(String::compareTo);
        for (String number : numbers) {
            Asset item = assetMapper.findByNoForUpdate(number);
            if (item == null) throw new BizException(404, "资产不存在：" + number);
            locked.put(number, item);
        }
        return locked;
    }

    private Asset lockOne(String no) {
        if (no == null || no.isBlank()) throw new BizException("该申请缺少设备编号");
        Asset a = assetMapper.findByNoForUpdate(no);
        if (a == null) throw new BizException(404, "资产不存在：" + no);
        return a;
    }

    private void checkNewAsset(AssetRequest r, Asset na, ApproveReq req) {
        if (req != null && req.getNewAssetCategory() != null && !req.getNewAssetCategory().equals(na.getCategory()))
            throw new BizException(409, "替换设备分类已变化，请重新选择");
        boolean idle = "闲置".equals(na.getStatus()) && !na.hasUser() && !na.hasBorrower();
        if (!idle) throw new BizException("替换设备必须处于闲置状态");
    }

    /** 按业务执行对应动作；REPLACE 由回收 + 配发两步组成 */
    private void applyStage(AssetRequest r, Asset a, Asset na, String operator) {
        switch (r.getType()) {
            case "RECEIVE" -> doReceive(a, r, operator);
            case "BORROW" -> doBorrow(a, r, operator);
            case "RETURN" -> doReturn(a, r, operator);
            case "REPLACE" -> {
                doReplaceRecycle(a, r, operator);
                doReplaceAssign(na, r, operator);
            }
            default -> throw new BizException("不支持的申请类型");
        }
    }

    private void doReceive(Asset a, AssetRequest r, String operator) {
        a.assignUser(r.getApplicantId(), r.getApplicant());
        a.setStatus("在用");
        a.assignBorrower(null, "");
        assetMapper.update(a);
        assetService.addHistory(a.getNo(), "领用", "领用给 " + r.getApplicant() + "（" + r.getNo() + "）", operator);
    }

    private void doBorrow(Asset a, AssetRequest r, String operator) {
        a.setStatus("借出中");
        a.assignBorrower(r.getApplicantId(), r.getApplicant());
        a.setBorrowDue(r.getExpectReturn());
        a.setBorrowSoonRemindedAt(null);
        a.setBorrowOverdueRemindedAt(null);
        assetMapper.update(a);
        assetService.addHistory(a.getNo(), "借用",
                "借出给 " + r.getApplicant() + "，约定归还 " + r.getExpectReturn() + "（" + r.getNo() + "）", operator);
    }

    private void doReturn(Asset a, AssetRequest r, String operator) {
        boolean borrowed = a.hasBorrower();
        if (borrowed) {
            // 归还借用设备：清借用人，设备回到原使用人手里（无人使用则回库）
            a.assignBorrower(null, "");
            a.setBorrowDue(null);
            a.setStatus(a.hasUser() ? "在用" : "闲置");
        } else {
            // 归还本人领用的设备：解除使用人并回库
            a.assignUser(null, "");
            a.assignBorrower(null, "");
            a.setBorrowDue(null);
            a.setStatus("闲置");
        }
        assetMapper.update(a);
        assetService.addHistory(a.getNo(), "归还", r.getApplicant() + " 归还设备，检查正常（" + r.getNo() + "）", operator);
    }

    private void doReplaceRecycle(Asset a, AssetRequest r, String operator) {
        a.assignUser(null, "");
        a.assignBorrower(null, "");
        a.setBorrowDue(null);
        a.setStatus("闲置");
        assetMapper.update(a);
        assetService.addHistory(a.getNo(), "更换",
                "因更换申请 " + r.getNo() + " 回收至备件库" + (r.getNewAssetNo() == null ? "" : "，替换机 " + r.getNewAssetNo()), operator);
    }

    private void doReplaceAssign(Asset na, AssetRequest r, String operator) {
        na.assignUser(r.getApplicantId(), r.getApplicant());
        na.setStatus("在用");
        na.assignBorrower(null, "");
        assetMapper.update(na);
        assetService.addHistory(na.getNo(), "更换",
                "替换 " + r.getAssetNo() + " 配发给 " + r.getApplicant() + "（" + r.getNo() + "）", operator);

        int moved = ticketMapper.transferAsset(r.getAssetNo(), na.getNo());
        r.setNewAssetNo(na.getNo());
        if (moved > 0) r.setRemark("转移未结工单 " + moved + " 张");
        notifyService.send(r.getApplicantId(), r.getApplicant(), "新设备已配发",
                r.getNo() + " 新设备 " + na.getNo() + " 已配发。", "ASSET", r.getNo());
    }

    /** 整单完成：清空交接态，写审批人与时间，通知申请人 */
    private void complete(AssetRequest r, String operator, ApproveReq req) {
        r.setStatus("COMPLETED");
        r.setHandoverStatus("");
        r.setApprover(operator);
        r.setHandledAt(LocalDateTime.now());
        if (req != null && req.getRemark() != null && !req.getRemark().isBlank()) r.setRemark(req.getRemark());
        requestMapper.update(r);
        if (!"REPLACE".equals(r.getType())) {
            notifyService.send(r.getApplicantId(), r.getApplicant(), "申请已完成",
                    r.getNo() + " " + TYPE_NAME.get(r.getType()) + " 已办理完成。", "ASSET", r.getNo());
        }
        audit.record("ASSET_APPROVE", r.getNo(), r.getType() + "；资产=" + r.getAssetNo()
                + "；申请人=" + r.getApplicant() + "；操作人=" + operator);
    }

    private void notifyHandoverNeeded(AssetRequest r) {
        boolean ret = "RETURN".equals(r.getHandoverStatus());
        notifyService.send(r.getApplicantId(), r.getApplicant(),
                ret ? "审批已通过，待归还设备" : "审批已通过，待领取设备",
                r.getNo() + " " + TYPE_NAME.get(r.getType())
                        + (ret ? " 已通过，请把设备交到 IT 服务台/备件库，IT 确认后完成。"
                               : " 已通过，请到 IT 服务台/备件库领取设备，IT 确认后完成。"),
                "ASSET", r.getNo());
    }

    @Transactional
    public AssetRequest reject(Long id, String reason) {
        LoginUser u = CurrentUser.require();
        if (!(u.hasPermission("request.approve"))) {
            throw new BizException(403, "只有资产管理员可以审批");
        }
        AssetRequest r = requestMapper.findByIdForUpdate(id);
        if (r == null) {
            throw new BizException(404, "申请单不存在");
        }
        if (r.getDingInstanceId() != null && !r.getDingInstanceId().isBlank()) {
            throw new BizException(409, "该申请在钉钉审批中（" + r.getDingInstanceId() + "），请到钉钉里处理");
        }
        return rejectAs(r, u.name(), reason);
    }

    /** 驳回的实际执行：系统内审批与钉钉轮询回写共用 */
    @Transactional
    public AssetRequest rejectAs(AssetRequest r, String operator, String reason) {
        if (r == null) {
            throw new BizException(404, "申请单不存在");
        }
        if (!"PENDING".equals(r.getStatus())) {
            throw new BizException("该申请已处理");
        }
        r.setStatus("REJECTED");
        r.setApprover(operator);
        r.setHandledAt(LocalDateTime.now());
        r.setRemark(reason);
        requestMapper.update(r);
        notifyService.send(r.getApplicantId(), r.getApplicant(), "申请被驳回",
                r.getNo() + " 驳回原因：" + reason, "ASSET", r.getNo());
        audit.record("ASSET_REJECT",r.getNo(),reason==null?"":reason);
        return requestMapper.findById(r.getId());
    }

    @Transactional
    public AssetRequest cancel(Long id) {
        LoginUser u = CurrentUser.require();
        AssetRequest r = requestMapper.findByIdForUpdate(id);
        if (r == null) {
            throw new BizException(404, "申请单不存在");
        }
        if (!r.getApplicant().equals(u.name())) {
            throw new BizException(403, "只能撤销自己的申请");
        }
        if (!"PENDING".equals(r.getStatus())) {
            throw new BizException("该申请已处理，不能撤销");
        }
        r.setStatus("CANCELLED");
        r.setHandledAt(LocalDateTime.now());
        r.setRemark("申请人主动撤销");
        requestMapper.update(r);
        return requestMapper.findById(id);
    }
}
