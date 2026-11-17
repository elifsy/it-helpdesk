package com.tzme.helpdesk.mapper;

import com.tzme.helpdesk.domain.AssetRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

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
                            @Param("type") String type);

    /** 同一人对同一设备待审批的申请：用于阻止重复提交 */
    @org.apache.ibatis.annotations.Select("select * from it_asset_request where applicant=#{applicant} and asset_no=#{assetNo} " +
            "and status='PENDING' order by id desc limit 1")
    AssetRequest findPendingByApplicantAndAsset(@Param("applicant") String applicant, @Param("assetNo") String assetNo);

    long countPending();
}
