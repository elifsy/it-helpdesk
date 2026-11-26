package com.tzme.helpdesk.service;

import com.tzme.helpdesk.common.BizException;
import com.tzme.helpdesk.common.CurrentUser;
import com.tzme.helpdesk.common.PageResult;
import com.tzme.helpdesk.config.HelpdeskProperties;
import com.tzme.helpdesk.domain.Asset;
import com.tzme.helpdesk.domain.AssetHistory;
import com.tzme.helpdesk.domain.User;
import com.tzme.helpdesk.dto.AssetCreateReq;
import com.tzme.helpdesk.mapper.AssetHistoryMapper;
import com.tzme.helpdesk.mapper.AssetMapper;
import com.tzme.helpdesk.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AssetService {

    private final AssetMapper assetMapper;
    private final NoGen numbers;
    private final UserMapper users;
    private final AssetHistoryMapper historyMapper;
    private final HelpdeskProperties props;
    private final RuntimeSettingsService settings;
    private final AssetCategoryService categories;
    private final com.tzme.helpdesk.mapper.ManagementMapper management;
    private final AuditService audit;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final com.tzme.helpdesk.mapper.AssetRequestMapper requestMapper;
    private final com.tzme.helpdesk.mapper.PurchaseRequestItemMapper purchaseItemMapper;

    /** 允许的卡片图标 key（与前端 utils/deviceIcons.ts 保持一致） */
    private static final java.util.Set<String> ICON_KEYS = java.util.Set.of(
            "laptop", "desktop", "monitor", "projector", "keyboard", "mouse", "headset",
            "printer", "server", "network", "phone", "tablet", "generic");

    /** 空或 auto = 自动匹配；其它值必须在白名单内 */
    private String icon(String value) {
        if (value == null || value.isBlank() || "auto".equalsIgnoreCase(value.trim())) return null;
        String key = value.trim();
        if (!ICON_KEYS.contains(key)) throw new BizException("不支持的卡片图标：" + key);
        return key;
    }

    @Transactional
    public Asset create(AssetCreateReq req) {
        com.tzme.helpdesk.security.Permissions.require("asset.create");
        management.lockSetting("management-lock");
        Asset created = createLocked(req, null);
        return assetMapper.findByNo(created.getNo());
    }

    @Transactional
    public int importBatch(List<AssetWorkbook.AssetRow> rows) {
        com.tzme.helpdesk.security.Permissions.require("asset.create");
        management.lockSetting("management-lock");
        List<User> people = rows.stream().anyMatch(row -> row.userName() != null || row.employeeNo() != null)
                ? users.listActive() : List.of();
        for (var row : rows) {
            String prefix = "第 " + row.number() + " 行：";
            try {
                createLocked(row.request(), resolveImportUser(row, people));
            } catch (BizException e) {
                throw new BizException(e.getCode(), prefix + e.getMessage());
            } catch (DuplicateKeyException e) {
                throw new BizException(409, prefix + "资产编号或ERP资产编号已存在，请重试");
            }
        }
        return rows.size();
    }

    private User resolveImportUser(AssetWorkbook.AssetRow row, List<User> people) {
        if (row.userName() == null && row.employeeNo() == null) return null;
        if (!"在用".equals(row.request().getStatus())) throw new BizException("填写使用人时，设备状态请留空或填写在用");
        if (row.employeeNo() != null) {
            var matching = people.stream().filter(user -> row.employeeNo().equalsIgnoreCase(user.getEmployeeNo())).toList();
            if (matching.isEmpty()) throw new BizException("通讯录中找不到工号“" + row.employeeNo() + "”的在职人员");
            if (matching.size() > 1) throw new BizException("工号“" + row.employeeNo() + "”重复，请先核对通讯录");
            User person = matching.get(0);
            if (row.userName() != null && !row.userName().equals(person.getName())) throw new BizException("使用人姓名与工号不匹配");
            return person;
        }
        var matching = people.stream().filter(user -> row.userName().equals(user.getName())).toList();
        if (matching.isEmpty()) throw new BizException("通讯录中找不到在职人员“" + row.userName() + "”，请核对使用人姓名");
        if (matching.size() > 1) throw new BizException("通讯录中有多名“" + row.userName() + "”，请按部门核对并填写使用人工号");
        return matching.get(0);
    }

    private Asset createLocked(AssetCreateReq req, User user) {
        Asset asset=new Asset();
        org.springframework.beans.BeanUtils.copyProperties(req,asset,"no");
        if(asset.getName()==null || asset.getName().isBlank() || asset.getName().length()>128) throw new BizException("请填写有效的资产名称");
        asset.setName(asset.getName().trim());
        String erpNo = optional(req.getErpNo() == null ? req.getNo() : req.getErpNo());
        requireAvailableErpNo(erpNo, null);
        asset.setErpNo(erpNo);
        asset.setCategory(categories.require(asset.getCategory()).getName());
        if(asset.getStatus()==null) asset.setStatus("待入库");
        validateDates(asset.getBuyDate(),asset.getWarrantyEnd());
        if("借出中".equals(asset.getStatus())) throw new BizException("借用状态请通过资产申请流程更新");
        asset.setModel(optional(asset.getModel())); asset.setSn(optional(asset.getSn())); asset.setLocation(optional(asset.getLocation()));
        asset.setWarranty(optional(asset.getWarranty())); asset.setRemark(optional(asset.getRemark()));
        asset.setStatus(statusForLocation(asset.getStatus(),asset.getLocation()));
        asset.setIcon(icon(req.getIcon()));
        if (user != null) asset.assignUser(user.getId(), user.getName());
        asset.setNo(numbers.nextAsset());
        assetMapper.insert(asset);
        String details = "分类=" + asset.getCategory() + (user == null ? "" : "；使用人=" + user.getName());
        addHistory(asset.getNo(),"入库","新资产入库（"+asset.getName()+"）；"+details,CurrentUser.name());
        audit.record("ASSET_CREATE",asset.getNo(),asset.getName()+"；"+details);
        return asset;
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
        List<Asset> rows = assetMapper.list(status, category, keyword);
        markReserved(rows);
        displayHolderNames(rows);
        return rows;
    }

    /** 给「已批准待交接」的设备打上占用信息：仅待领取（PICKUP）代表设备已经许给别人 */
    private void markReserved(List<Asset> rows) {
        if (rows == null || rows.isEmpty()) return;
        Map<String, String[]> reserved = new java.util.HashMap<>();
        for (Map<String, Object> m : requestMapper.listReserved()) {
            if (!"PICKUP".equals(String.valueOf(m.get("handoverStatus")))) continue;
            reserved.put(String.valueOf(m.get("assetNo")), new String[]{
                    m.get("applicant") == null ? "" : String.valueOf(m.get("applicant")),
                    m.get("no") == null ? "" : String.valueOf(m.get("no"))});
        }
        // 购入申请已入库待领取的资产同样占用
        java.util.List<java.util.Map<String, Object>> purchase = purchaseItemMapper.listReserved();
        if (purchase != null) {
            for (java.util.Map<String, Object> m : purchase) {
                reserved.putIfAbsent(String.valueOf(m.get("assetNo")), new String[]{
                        m.get("applicant") == null ? "" : String.valueOf(m.get("applicant")),
                        m.get("no") == null ? "" : String.valueOf(m.get("no"))});
            }
        }
        if (reserved.isEmpty()) return;
        for (Asset a : rows) {
            String[] hit = reserved.get(a.getNo());
            if (hit != null) {
                a.setReservedBy(hit[0]);
                a.setReservedRequestNo(hit[1]);
            }
        }
    }

    public PageResult<Asset> page(String status, String category, String keyword, int page, int size) {
        int p = Math.max(1, page), s = Math.max(1, Math.min(100, size));
        List<Asset> rows = assetMapper.page(status, category, keyword, (long) (p - 1) * s, s);
        displayHolderNames(rows);
        return new PageResult<>(rows, assetMapper.count(status, category, keyword), p, s);
    }

    private void displayHolderNames(List<Asset> assets) {
        List<Long> ids = assets.stream().flatMap(asset -> java.util.stream.Stream.of(asset.getUserId(), asset.getBorrowerId()))
                .filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return;
        Map<Long, String> names = users.adminListByIds(ids).stream().collect(Collectors.toMap(User::getId, User::getName));
        for (Asset asset : assets) {
            if (names.containsKey(asset.getUserId())) asset.setUserName(names.get(asset.getUserId()));
            if (names.containsKey(asset.getBorrowerId())) asset.setBorrower(names.get(asset.getBorrowerId()));
        }
    }

    public record HolderOption(Long id, String name, String department, String employeeNo) { }

    public List<HolderOption> searchHolders(String keyword) {
        com.tzme.helpdesk.security.Permissions.require("asset.create");
        return users.searchActivePeople(keyword, false).stream()
                .map(user -> new HolderOption(user.getId(), user.getName(), user.getDeptName(), user.getEmployeeNo())).toList();
    }

    @Transactional
    public Map<String,Object> bindHolders(String no, String token, Long userId, Long borrowerId) {
        com.tzme.helpdesk.security.Permissions.require("asset.create");
        management.lockSetting("management-lock");
        Asset asset = assetMapper.findByNoForUpdate(no);
        if (asset == null) throw new BizException(404, "资产不存在");
        if (!editToken(asset).equals(token)) throw new BizException(409, "设备信息已变化，请重新加载后确认归属");
        if (userId == null && borrowerId == null) throw new BizException("请先选择要绑定的人员");
        List<String> changes = new ArrayList<>();
        if (userId != null) {
            if (asset.getUserId() != null || !asset.hasUser()) throw new BizException(409, "该设备没有待确认的使用人");
            User holder = requireActiveHolder(userId);
            changes.add("使用人：" + asset.getUserName() + " → " + holder.getName() + "（账号 " + userId + "）");
            asset.assignUser(userId, holder.getName());
        }
        if (borrowerId != null) {
            if (asset.getBorrowerId() != null || !asset.hasBorrower()) throw new BizException(409, "该设备没有待确认的借用人");
            User holder = requireActiveHolder(borrowerId);
            changes.add("借用人：" + asset.getBorrower() + " → " + holder.getName() + "（账号 " + borrowerId + "）");
            asset.assignBorrower(borrowerId, holder.getName());
        }
        assetMapper.update(asset);
        String summary = String.join("；", changes);
        addHistory(no, "确认资产归属", summary, CurrentUser.name());
        audit.record("ASSET_HOLDER_BIND", no, summary);
        return detail(no);
    }

    private User requireActiveHolder(Long id) {
        User user = users.findById(id);
        if (user == null || !"ACTIVE".equals(user.getStatus())) throw new BizException("请选择有效的在职人员");
        return user;
    }

    User resolveApplicant(Long id, String name) {
        if (id != null) {
            User user = users.findById(id);
            if (user == null) throw new BizException(409, "申请人账号已不存在，请核对申请");
            return user;
        }
        List<User> matching = users.listByExactName(name);
        if (matching.size() != 1) throw new BizException(409, "历史申请的人员身份无法确认，请联系申请人重新提交");
        return matching.get(0);
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
        String icon=icon(req.getIcon());
        String erpNo = req.isErpNoProvided() ? optional(req.getErpNo()) : asset.getErpNo();
        requireAvailableErpNo(erpNo, asset.getNo());
        List<String> changes=new ArrayList<>();
        changed(changes,"名称",asset.getName(),name); changed(changes,"分类",asset.getCategory(),category);
        changed(changes,"ERP资产编号",asset.getErpNo(),erpNo);
        changed(changes,"型号",asset.getModel(),optional(req.getModel()));
        changed(changes,"SN",asset.getSn(),optional(req.getSn())); changed(changes,"位置",asset.getLocation(),location);
        changed(changes,"购置日期",asset.getBuyDate(),req.getBuyDate()); changed(changes,"维保方式",asset.getWarranty(),optional(req.getWarranty()));
        changed(changes,"维保到期",asset.getWarrantyEnd(),req.getWarrantyEnd()); changed(changes,"状态",asset.getStatus(),status);
        changed(changes,"备注",asset.getRemark(),optional(req.getRemark()));
        changed(changes,"图标",asset.getIcon(),icon);
        if(!changes.isEmpty()) {
            asset.setErpNo(erpNo);
            asset.setName(name); asset.setModel(optional(req.getModel())); asset.setCategory(category); asset.setSn(optional(req.getSn())); asset.setLocation(location);
            asset.setBuyDate(req.getBuyDate()); asset.setWarranty(optional(req.getWarranty())); asset.setWarrantyEnd(req.getWarrantyEnd());
            asset.setStatus(status); asset.setRemark(optional(req.getRemark())); asset.setIcon(icon);
            assetMapper.updateDetails(asset);
            addHistory(no,"编辑设备","更新设备资料："+String.join("、",changes.stream().map(item -> item.substring(0,item.indexOf('：'))).toList()),CurrentUser.name());
            audit.record("ASSET_UPDATE",no,String.join("；",changes));
        }
        return detail(no);
    }

    private String optional(String value) { return value==null || value.isBlank() ? null : value.trim(); }
    private void requireAvailableErpNo(String erpNo, String currentNo) {
        if (erpNo == null) return;
        String existingNo = assetMapper.findNoByErpNo(erpNo);
        if (existingNo != null && !existingNo.equals(currentNo)) throw new BizException(409, "ERP资产编号已存在");
    }
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
        if("借出中".equals(asset.getStatus()) || asset.hasBorrower() || asset.hasUser()) return List.of(asset.getStatus());
        return List.of("待入库","闲置","在用","维修中","待报废","已报废");
    }
    private String editToken(Asset asset) {
        try {
            byte[] value=json.writeValueAsBytes(java.util.Arrays.asList(asset.getNo(),asset.getErpNo(),asset.getName(),asset.getModel(),asset.getCategory(),asset.getSn(),
                    asset.getUserName(),asset.getUserId(),asset.getLocation(),asset.getBuyDate(),asset.getWarranty(),asset.getWarrantyEnd(),asset.getStatus(),
                    asset.getBorrower(),asset.getBorrowerId(),asset.getBorrowDue(),asset.getRemark(),asset.getUpdatedAt()));
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
        m.put("actions", actions(a));
        m.put("editToken",editToken(a));
        m.put("editableStatuses",editableStatuses(a));
        displayHolderNames(List.of(a));
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
    public List<String> actions(Asset a) {
        var user=CurrentUser.require();
        return stateActions(a,user.id()).stream().filter(action -> "REPAIR".equals(action) ? user.hasPermission("ticket.create") : user.hasPermission("request.create")).toList();
    }

    List<String> stateActions(Asset a, Long userId) {
        List<String> acts = new ArrayList<>();
        if (a == null || userId == null) {
            return acts;
        }
        if (!"已报废".equals(a.getStatus())) {
            acts.add("REPAIR");
        }
        if (("闲置".equals(a.getStatus()) || "待入库".equals(a.getStatus()))
                && !a.hasUser() && !a.hasBorrower()) {
            acts.add("RECEIVE");
            acts.add("BORROW");
        }
        if ("在用".equals(a.getStatus()) && !a.hasUser() && !a.hasBorrower()) {
            acts.add("BORROW");
        }
        if ("在用".equals(a.getStatus()) && userId.equals(a.getUserId())) {
            acts.add("REPLACE");
            // 本人领用的设备也可以直接发起归还（退库），审批后解除使用人
            acts.add("RETURN");
        }
        if ("借出中".equals(a.getStatus()) && userId.equals(a.getBorrowerId())) {
            acts.add("RETURN");
        }
        return acts;
    }

    public void assertAllowed(Asset a, String type, Long userId) {
        if (!stateActions(a, userId).contains(type)) {
            if (("REPLACE".equals(type) || "RETURN".equals(type))
                    && ((a.hasUser() && a.getUserId() == null) || (a.hasBorrower() && a.getBorrowerId() == null)))
                throw new BizException(409, "该设备归属待确认，请联系 IT 在资产详情中绑定人员账号");
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
