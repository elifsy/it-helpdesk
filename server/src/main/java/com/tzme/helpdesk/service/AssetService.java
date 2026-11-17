package com.tzme.helpdesk.service;

import com.tzme.helpdesk.common.BizException;
import com.tzme.helpdesk.common.CurrentUser;
import com.tzme.helpdesk.common.PageResult;
import com.tzme.helpdesk.config.HelpdeskProperties;
import com.tzme.helpdesk.domain.Asset;
import com.tzme.helpdesk.domain.AssetHistory;
import com.tzme.helpdesk.mapper.AssetHistoryMapper;
import com.tzme.helpdesk.mapper.AssetMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AssetService {

    private final AssetMapper assetMapper;
    private final AssetHistoryMapper historyMapper;
    private final HelpdeskProperties props;
    private final RuntimeSettingsService settings;
    private final AssetCategoryService categories;
    private final com.tzme.helpdesk.mapper.ManagementMapper management;
    private final AuditService audit;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    @org.springframework.transaction.annotation.Transactional
    public Asset create(com.tzme.helpdesk.dto.AssetCreateReq req) {
        com.tzme.helpdesk.security.Permissions.require("asset.create");
        management.lockSetting("management-lock");
        Asset asset=new Asset();
        org.springframework.beans.BeanUtils.copyProperties(req,asset);
        if(asset.getNo()==null || asset.getNo().isBlank() || asset.getNo().length()>32) throw new BizException("请填写不超过32字的资产编号");
        if(asset.getName()==null || asset.getName().isBlank() || asset.getName().length()>128) throw new BizException("请填写有效的资产名称");
        asset.setNo(asset.getNo().trim()); asset.setName(asset.getName().trim());
        if(assetMapper.findByNo(asset.getNo())!=null) throw new BizException(409,"资产编号已存在");
        asset.setCategory(categories.require(asset.getCategory()).getName());
        if(asset.getStatus()==null) asset.setStatus("待入库");
        validateDates(asset.getBuyDate(),asset.getWarrantyEnd());
        if("借出中".equals(asset.getStatus())) throw new BizException("借用状态请通过资产申请流程更新");
        asset.setModel(optional(asset.getModel())); asset.setSn(optional(asset.getSn())); asset.setLocation(optional(asset.getLocation()));
        asset.setWarranty(optional(asset.getWarranty())); asset.setRemark(optional(asset.getRemark()));
        asset.setStatus(statusForLocation(asset.getStatus(),asset.getLocation()));
        assetMapper.insert(asset);
        addHistory(asset.getNo(),"入库","新资产入库（"+asset.getName()+"）；分类="+asset.getCategory(),CurrentUser.name());
        audit.record("ASSET_CREATE",asset.getNo(),asset.getName()+"；分类="+asset.getCategory());
        return assetMapper.findByNo(asset.getNo());
    }

    @org.springframework.transaction.annotation.Transactional
    public Asset changeCategory(String no,String category) {
        com.tzme.helpdesk.security.Permissions.require("asset.create");
        management.lockSetting("management-lock");
        String target=categories.require(category).getName();
        Asset asset=assetMapper.findByNoForUpdate(no);
        if(asset==null) throw new BizException(404,"资产不存在");
        String previous=asset.getCategory();
        if(!target.equals(previous)) {
            asset.setCategory(target); assetMapper.update(asset);
            addHistory(no,"调整分类",previous+" → "+target,CurrentUser.name());
            audit.record("ASSET_CATEGORY_ASSIGN",no,previous+" → "+target);
        }
        return assetMapper.findByNo(no);
    }

    public List<Asset> list(String status, String category, String keyword) {
        return assetMapper.list(status, category, keyword);
    }

    public PageResult<Asset> page(String status, String category, String keyword, int page, int size) {
        int p = Math.max(1, page), s = Math.max(1, Math.min(100, size));
        List<Asset> rows = assetMapper.page(status, category, keyword, (long) (p - 1) * s, s);
        return new PageResult<>(rows, assetMapper.count(status, category, keyword), p, s);
    }

    @org.springframework.transaction.annotation.Transactional
    public Map<String,Object> updateDetails(String no,com.tzme.helpdesk.dto.AssetUpdateReq req) {
        com.tzme.helpdesk.security.Permissions.require("asset.create");
        management.lockSetting("management-lock");
        Asset asset=assetMapper.findByNoForUpdate(no);
        if(asset==null) throw new BizException(404,"资产不存在");
        if(!editToken(asset).equals(req.getEditToken())) throw new BizException(409,"设备信息已变化，请重新加载后再编辑");
        String name=req.getName().trim();
        if(name.isEmpty()) throw new BizException("请填写设备名称");
        validateDates(req.getBuyDate(),req.getWarrantyEnd());
        String location=optional(req.getLocation());
        String status=statusForLocation(req.getStatus(),location);
        if(!editableStatuses(asset).contains(status))
            throw new BizException(409,"设备已分配或借出，请通过资产申请流程更新使用状态");
        String category=categories.require(req.getCategory()).getName();
        List<String> changes=new ArrayList<>();
        changed(changes,"名称",asset.getName(),name); changed(changes,"分类",asset.getCategory(),category);
        changed(changes,"型号",asset.getModel(),optional(req.getModel()));
        changed(changes,"SN",asset.getSn(),optional(req.getSn())); changed(changes,"位置",asset.getLocation(),location);
        changed(changes,"购置日期",asset.getBuyDate(),req.getBuyDate()); changed(changes,"维保方式",asset.getWarranty(),optional(req.getWarranty()));
        changed(changes,"维保到期",asset.getWarrantyEnd(),req.getWarrantyEnd()); changed(changes,"状态",asset.getStatus(),status);
        changed(changes,"备注",asset.getRemark(),optional(req.getRemark()));
        if(!changes.isEmpty()) {
            asset.setName(name); asset.setModel(optional(req.getModel())); asset.setCategory(category); asset.setSn(optional(req.getSn())); asset.setLocation(location);
            asset.setBuyDate(req.getBuyDate()); asset.setWarranty(optional(req.getWarranty())); asset.setWarrantyEnd(req.getWarrantyEnd());
            asset.setStatus(status); asset.setRemark(optional(req.getRemark()));
            assetMapper.updateDetails(asset);
            addHistory(no,"编辑设备","更新设备资料："+String.join("、",changes.stream().map(item -> item.substring(0,item.indexOf('：'))).toList()),CurrentUser.name());
            audit.record("ASSET_UPDATE",no,String.join("；",changes));
        }
        return detail(no);
    }

    private String optional(String value) { return value==null || value.isBlank() ? null : value.trim(); }
    private String statusForLocation(String status,String location) {
        return "待入库".equals(status) && location!=null && !location.isBlank() ? "闲置" : status;
    }
    private void validateDates(java.time.LocalDate buyDate,java.time.LocalDate warrantyEnd) {
        if(buyDate!=null && warrantyEnd!=null && warrantyEnd.isBefore(buyDate)) throw new BizException("维保到期日期不能早于购置日期");
    }
    private void changed(List<String> changes,String label,Object before,Object after) {
        if(!java.util.Objects.equals(before,after)) changes.add(label+"："+(before==null?"未填写":before)+" → "+(after==null?"未填写":after));
    }
    private List<String> editableStatuses(Asset asset) {
        if("借出中".equals(asset.getStatus()) || (asset.getBorrower()!=null && !asset.getBorrower().isBlank())
                || (asset.getUserName()!=null && !asset.getUserName().isBlank())) return List.of(asset.getStatus());
        return List.of("待入库","闲置","在用","维修中","待报废","已报废");
    }
    private String editToken(Asset asset) {
        try {
            byte[] value=json.writeValueAsBytes(java.util.Arrays.asList(asset.getNo(),asset.getName(),asset.getModel(),asset.getCategory(),asset.getSn(),
                    asset.getUserName(),asset.getLocation(),asset.getBuyDate(),asset.getWarranty(),asset.getWarrantyEnd(),asset.getStatus(),
                    asset.getBorrower(),asset.getBorrowDue(),asset.getRemark(),asset.getUpdatedAt()));
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));
        } catch(java.security.NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("生成设备编辑版本失败",e); }
    }

    public Asset requireAsset(String no) {
        Asset a = assetMapper.findByNo(no);
        if (a == null) {
            throw new BizException(404, "资产不存在：" + no);
        }
        return a;
    }

    public Map<String, Object> detail(String no) {
        Asset a = requireAsset(no);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("asset", a);
        m.put("history", historyMapper.listByNo(no));
        m.put("actions", actions(a, CurrentUser.name()));
        m.put("editToken",editToken(a));
        m.put("editableStatuses",editableStatuses(a));
        return m;
    }

    public String qrcode(String no) {
        requireAsset(no);
        String base = settings.get().getQrcodeBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/#/assets?asset=" + java.net.URLEncoder.encode(no, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 设备当前状态下可发起的操作。设备操作台与「资产服务」入口共用这一处判断，
     * 避免两条路径给出不一致的可选操作。
     */
    public List<String> actions(Asset a, String userName) {
        var user=CurrentUser.require();
        return stateActions(a,userName).stream().filter(action -> "REPAIR".equals(action) ? user.hasPermission("ticket.create") : user.hasPermission("request.create")).toList();
    }

    private List<String> stateActions(Asset a,String userName) {
        List<String> acts = new ArrayList<>();
        if (a == null || userName == null) {
            return acts;
        }
        String u = userName;
        if (!"已报废".equals(a.getStatus())) {
            acts.add("REPAIR");
        }
        if (("闲置".equals(a.getStatus()) || "待入库".equals(a.getStatus()))
                && (a.getUserName() == null || a.getUserName().isBlank())
                && (a.getBorrower() == null || a.getBorrower().isBlank())) {
            acts.add("RECEIVE");
            acts.add("BORROW");
        }
        if ("在用".equals(a.getStatus()) && (a.getUserName() == null || a.getUserName().isBlank())
                && (a.getBorrower()==null || a.getBorrower().isBlank())) {
            acts.add("BORROW");
        }
        if ("在用".equals(a.getStatus()) && u.equals(a.getUserName())) {
            acts.add("REPLACE");
            // 本人领用的设备也可以直接发起归还（退库），审批后解除使用人
            acts.add("RETURN");
        }
        if ("借出中".equals(a.getStatus()) && u.equals(a.getBorrower())) {
            acts.add("RETURN");
        }
        return acts;
    }

    public void assertAllowed(Asset a, String type, String userName) {
        if (!stateActions(a, userName).contains(type)) {
            String reason = switch (type) {
                case "RECEIVE" -> "只有「闲置 / 待入库」的设备可以申请领用";
                case "BORROW" -> "借用只对闲置设备或未分配使用人的公用设备开放";
                case "REPLACE" -> "更换只对自己名下的在用设备开放";
                case "RETURN" -> "归还只对自己借出中的设备开放";
                default -> "当前设备状态不允许该操作";
            };
            throw new BizException(reason);
        }
    }

    public void addHistory(String assetNo, String action, String detail, String operator) {
        AssetHistory h = new AssetHistory();
        h.setAssetNo(assetNo);
        h.setAction(action);
        h.setDetail(detail);
        h.setOperator(operator);
        historyMapper.insert(h);
    }
}
