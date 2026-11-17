package com.tzme.helpdesk.service;
import com.tzme.helpdesk.common.BizException;
import com.tzme.helpdesk.domain.AssetCategory;
import com.tzme.helpdesk.dto.AssetCategoryReq;
import com.tzme.helpdesk.mapper.*;
import com.tzme.helpdesk.security.Permissions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
@Service
@RequiredArgsConstructor
public class AssetCategoryService {
    private final AssetCategoryMapper categories;
    private final ManagementMapper management;
    private final AuditService audit;
    public List<AssetCategory> list() { return categories.list(); }
    public AssetCategory require(String name) {
        AssetCategory category=name==null ? null : categories.findByName(name);
        if(category==null) throw new BizException("资产分类不存在，请重新选择");
        return category;
    }
    @Transactional public AssetCategory save(Long id,AssetCategoryReq req) {
        Permissions.require("asset.create"); management.lockSetting("management-lock");
        String name=req.getName().trim();
        if(name.isEmpty() || name.chars().anyMatch(Character::isISOControl)) throw new BizException("请填写有效的分类名称");
        AssetCategory old=id==null ? null : categories.findById(id);
        if(id!=null && old==null) throw new BizException(404,"资产分类不存在");
        AssetCategory existing=categories.findByName(name);
        if(existing!=null && !existing.getId().equals(id)) throw new BizException(409,"该资产分类已存在");
        AssetCategory value=new AssetCategory(); value.setId(id); value.setName(name); value.setSort(req.getSort());
        int moved=0;
        if(id==null) categories.insert(value);
        else {
            categories.update(value);
            if(!old.getName().equals(name)) moved=categories.renameAssets(old.getName(),name);
        }
        value.setAssetCount(categories.countAssets(name));
        audit.record(id==null?"ASSET_CATEGORY_CREATE":"ASSET_CATEGORY_UPDATE",String.valueOf(value.getId()),
                (old==null?"新增":old.getName()+" → ")+name+"；同步资产="+moved);
        return value;
    }
    @Transactional public void delete(Long id) {
        Permissions.require("asset.create"); management.lockSetting("management-lock");
        AssetCategory category=categories.findById(id);
        if(category==null) throw new BizException(404,"资产分类不存在");
        if(categories.countAssets(category.getName())>0) throw new BizException(409,"该分类下仍有资产，请先调整资产分类");
        categories.delete(id); audit.record("ASSET_CATEGORY_DELETE",String.valueOf(id),category.getName());
    }
}
