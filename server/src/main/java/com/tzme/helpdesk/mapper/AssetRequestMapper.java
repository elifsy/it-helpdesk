package com.tzme.helpdesk.mapper;

import com.tzme.helpdesk.domain.AssetRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface AssetRequestMapper {
    int insert(AssetRequest r);

    int update(AssetRequest r);

    AssetRequest findById(@Param("id") Long id);

    @org.apache.ibatis.annotations.Select("select * from it_asset_request where id=#{id} for update")
    AssetRequest findByIdForUpdate(@Param("id") Long id);

    @org.apache.ibatis.annotations.Select("select * from it_asset_request where no=#{no} for update")
    AssetRequest findByNoForUpdate(@Param("no") String no);

    AssetRequest findByNo(@Param("no") String no);

    List<AssetRequest> list(@Param("status") String status,
                            @Param("applicant") String applicant,
                            @Param("applicantId") Long applicantId,
                            @Param("type") String type,
                            @Param("no") String no);

    /** 同一人对同一设备待审批的申请：用于阻止重复提交 */
    @org.apache.ibatis.annotations.Select("select * from it_asset_request where applicant=#{applicant} and asset_no=#{assetNo} " +
            "and status not in ('COMPLETED','REJECTED','CANCELLED') order by id desc limit 1")
    AssetRequest findPendingByApplicantAndAsset(@Param("applicant") String applicant, @Param("assetNo") String assetNo);

    /** 已发起钉钉审批、仍等待结果的申请：轮询同步用 */
    @org.apache.ibatis.annotations.Select("select r.*, a.name as assetName, a.category as assetCategory " +
            "from it_asset_request r left join it_asset a on a.no = r.asset_no " +
            "where r.status='PENDING' and r.ding_instance_id is not null and r.ding_instance_id <> '' " +
            "order by r.id asc limit 50")
    List<AssetRequest> listDingPending();

    /** 已批准且仍在待交接（待领取/待归还）的申请：用于给资产打「已占用」标记 */
    @org.apache.ibatis.annotations.Select("select asset_no as assetNo, applicant, no, handover_status as handoverStatus " +
            "from it_asset_request where status='APPROVED' and handover_status is not null and handover_status <> ''")
    List<Map<String, Object>> listReserved();

    /** 某设备是否已被「已批准待领取」的申请占用 */
    @org.apache.ibatis.annotations.Select("select * from it_asset_request where asset_no=#{assetNo} " +
            "and status='APPROVED' and handover_status='PICKUP' order by id desc limit 1")
    AssetRequest findReservedByAsset(@Param("assetNo") String assetNo);

    long countPending();
}
