package com.tzme.helpdesk.mapper;

import com.tzme.helpdesk.domain.PurchaseRequestItem;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface PurchaseRequestItemMapper {

    @Insert("insert into it_purchase_request_item(request_id, catalog_id, item_name, category, spec, status) " +
            "values(#{requestId}, #{catalogId}, #{itemName}, #{category}, #{spec}, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(PurchaseRequestItem item);

    @Select("select i.*, a.name as assetName from it_purchase_request_item i " +
            "left join it_asset a on a.no = i.asset_no where i.request_id = #{requestId} order by i.id asc")
    List<PurchaseRequestItem> listByRequest(@Param("requestId") Long requestId);

    @Select("select i.*, a.name as assetName from it_purchase_request_item i " +
            "left join it_asset a on a.no = i.asset_no where i.id = #{id}")
    PurchaseRequestItem findById(@Param("id") Long id);

    @Select("select * from it_purchase_request_item where id = #{id} for update")
    PurchaseRequestItem findByIdForUpdate(@Param("id") Long id);

    @Update("update it_purchase_request_item set status = #{status}, erp_no = #{erpNo}, " +
            "ordered_at = #{orderedAt}, asset_no = #{assetNo}, delivered_by = #{deliveredBy}, " +
            "delivered_at = #{deliveredAt}, remark = #{remark} where id = #{id}")
    int update(PurchaseRequestItem item);

    @Select("select count(*) from it_purchase_request_item where request_id = #{requestId}")
    int countByRequest(@Param("requestId") Long requestId);

    @Select("select count(*) from it_purchase_request_item where request_id = #{requestId} and status = 'COMPLETED'")
    int countDone(@Param("requestId") Long requestId);

    /** 某台设备是否已被其它明细占用（待领取） */
    @Select("select * from it_purchase_request_item where asset_no = #{assetNo} and status = 'PENDING_PICKUP' limit 1")
    PurchaseRequestItem findPendingByAsset(@Param("assetNo") String assetNo);

    /** 明细里「待领取」的设备：用于给资产打预留标记 */
    @Select("select i.asset_no as assetNo, r.applicant as applicant, r.no as no " +
            "from it_purchase_request_item i join it_purchase_request r on r.id = i.request_id " +
            "where i.status = 'PENDING_PICKUP' and i.asset_no is not null and i.asset_no <> ''")
    List<Map<String, Object>> listReserved();
}
