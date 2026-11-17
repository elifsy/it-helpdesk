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
    private final NoGen noGen;
    private final AuditService audit;

    public List<AssetRequest> list(String status, Boolean mine, String type) {
        LoginUser u = CurrentUser.require();
        boolean mineFlag = Boolean.TRUE.equals(mine) || u.isEmployeeOnly();
        return requestMapper.list(status, mineFlag ? u.name() : null, type);
    }

    @Transactional
    public AssetRequest create(AssetRequestCreateReq req) {
        LoginUser u = CurrentUser.require();
        String type = req.getType() == null ? "" : req.getType().toUpperCase();
        if (!TYPES.contains(type)) {
            throw new BizException("不支持的申请类型");
        }
        Asset a = assetMapper.findByNoForUpdate(req.getAssetNo());
        if (a == null) throw new BizException(404, "资产不存在");
        if(req.getCategory()!=null && !req.getCategory().equals(a.getCategory())) throw new BizException(409,"设备分类已变化，请重新选择设备");
        assetService.assertAllowed(a, type, u.name());

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

        AssetRequest r = new AssetRequest();
        r.setNo(noGen.next("AS"));
        r.setType(type);
        r.setAssetNo(req.getAssetNo());
        r.setApplicant(u.name());
        r.setDept(u.deptName());
        r.setReason(req.getReason());
        r.setExpectReturn(req.getExpectReturn());
        requestMapper.insert(r);

        notifyService.notifyPermission("request.approve", "有新的资产申请",
                r.getNo() + " " + TYPE_NAME.get(type) + " · " + a.getName() + "（" + u.name() + "）", "ASSET", r.getNo());
        notifyService.send(u.name(), "申请已提交",
                r.getNo() + " " + TYPE_NAME.get(type) + " 已提交，等待审批。", "ASSET", r.getNo());
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
        if (!"PENDING".equals(r.getStatus())) {
            throw new BizException("该申请已处理");
        }
        String newNo = req == null ? null : req.getNewAssetNo();
        if ("REPLACE".equals(r.getType()) && (newNo == null || newNo.isBlank() || newNo.equals(r.getAssetNo()))) {
            throw new BizException("更换审批必须指定另一台闲置设备");
        }
        // 两台设备按固定顺序加锁，避免交叉换机时锁顺序相反。
        java.util.Map<String, Asset> locked = new java.util.HashMap<>();
        java.util.List<String> numbers = new java.util.ArrayList<>(java.util.List.of(r.getAssetNo()));
        if ("REPLACE".equals(r.getType())) numbers.add(newNo);
        numbers.sort(String::compareTo);
        for (String number : numbers) {
            Asset item = assetMapper.findByNoForUpdate(number);
            if (item == null) throw new BizException(404, "资产不存在：" + number);
            locked.put(number, item);
        }
        Asset a = locked.get(r.getAssetNo());
        assetService.assertAllowed(a, r.getType(), r.getApplicant());
        if ("BORROW".equals(r.getType()) && (r.getExpectReturn() == null || r.getExpectReturn().isBefore(LocalDate.now()))) {
            throw new BizException("约定归还日期已过期，请重新申请");
        }

        if ("RECEIVE".equals(r.getType())) {
            a.setUserName(r.getApplicant());
            a.setStatus("在用");
            assetMapper.update(a);
            assetService.addHistory(a.getNo(), "领用", "领用给 " + r.getApplicant() + "（" + r.getNo() + "）", u.name());

        } else if ("BORROW".equals(r.getType())) {
            a.setStatus("借出中");
            a.setBorrower(r.getApplicant());
            a.setBorrowDue(r.getExpectReturn());
            assetMapper.update(a);
            assetService.addHistory(a.getNo(), "借用",
                    "借出给 " + r.getApplicant() + "，约定归还 " + r.getExpectReturn() + "（" + r.getNo() + "）", u.name());

        } else if ("RETURN".equals(r.getType())) {
            boolean borrowed = a.getBorrower() != null && !a.getBorrower().isBlank();
            if (borrowed) {
                // 归还借用设备：清借用人，设备回到原使用人手里（无人使用则回库）
                a.setBorrower("");
                a.setBorrowDue(null);
                a.setStatus(a.getUserName() == null || a.getUserName().isBlank() ? "闲置" : "在用");
            } else {
                // 归还本人领用的设备：解除使用人并回库
                a.setUserName("");
                a.setBorrower("");
                a.setBorrowDue(null);
                a.setStatus("闲置");
            }
            assetMapper.update(a);
            assetService.addHistory(a.getNo(), "归还", r.getApplicant() + " 归还设备，检查正常（" + r.getNo() + "）", u.name());

        } else if ("REPLACE".equals(r.getType())) {
            Asset na = locked.get(newNo);
            if(req.getNewAssetCategory()!=null && !req.getNewAssetCategory().equals(na.getCategory())) throw new BizException(409,"替换设备分类已变化，请重新选择");
            if (!"闲置".equals(na.getStatus()) || (na.getUserName() != null && !na.getUserName().isBlank())
                    || (na.getBorrower() != null && !na.getBorrower().isBlank())) {
                throw new BizException("替换设备必须处于闲置状态");
            }
            na.setUserName(r.getApplicant());
            na.setStatus("在用");
            assetMapper.update(na);
            assetService.addHistory(na.getNo(), "更换",
                    "替换 " + a.getNo() + " 配发给 " + r.getApplicant() + "（" + r.getNo() + "）", u.name());

            a.setUserName("");
            a.setStatus("闲置");
            assetMapper.update(a);
            assetService.addHistory(a.getNo(), "更换",
                    "因更换申请 " + r.getNo() + " 回收至备件库，替换机 " + na.getNo(), u.name());

            int moved = ticketMapper.transferAsset(a.getNo(), na.getNo());
            r.setNewAssetNo(na.getNo());
            if (moved > 0) {
                r.setRemark("转移未结工单 " + moved + " 张");
            }
            notifyService.send(r.getApplicant(), "更换申请已通过",
                    r.getNo() + " 已通过，新设备 " + na.getNo() + " 已配发。", "ASSET", r.getNo());
        }

        r.setStatus("COMPLETED");
        r.setApprover(u.name());
        r.setHandledAt(LocalDateTime.now());
        if (req != null && req.getRemark() != null && !req.getRemark().isBlank()) {
            r.setRemark(req.getRemark());
        }
        requestMapper.update(r);

        if (!"REPLACE".equals(r.getType())) {
            notifyService.send(r.getApplicant(), "申请已通过",
                    r.getNo() + " " + TYPE_NAME.get(r.getType()) + " 已批准。", "ASSET", r.getNo());
        }
        audit.record("ASSET_APPROVE",r.getNo(),r.getType()+"；资产="+r.getAssetNo()+"；申请人="+r.getApplicant());
        return requestMapper.findById(id);
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
        if (!"PENDING".equals(r.getStatus())) {
            throw new BizException("该申请已处理");
        }
        r.setStatus("REJECTED");
        r.setApprover(u.name());
        r.setHandledAt(LocalDateTime.now());
        r.setRemark(reason);
        requestMapper.update(r);
        notifyService.send(r.getApplicant(), "申请被驳回",
                r.getNo() + " 驳回原因：" + reason, "ASSET", r.getNo());
        audit.record("ASSET_REJECT",r.getNo(),reason==null?"":reason);
        return requestMapper.findById(id);
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
