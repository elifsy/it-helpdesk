package com.tzme.helpdesk.mapper;

import com.tzme.helpdesk.domain.Asset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AssetMapper {
    int insert(Asset a);

    Asset findByNo(@Param("no") String no);

    @org.apache.ibatis.annotations.Select("select no from it_asset where erp_no=#{erpNo}")
    String findNoByErpNo(@Param("erpNo") String erpNo);

    @org.apache.ibatis.annotations.Select("select * from it_asset where no=#{no} for update")
    Asset findByNoForUpdate(@Param("no") String no);

    int update(Asset a);

    @org.apache.ibatis.annotations.Update("update it_asset set erp_no=#{erpNo},name=#{name},model=#{model},category=#{category},sn=#{sn},location=#{location},buy_date=#{buyDate},warranty=#{warranty},warranty_end=#{warrantyEnd},status=#{status},remark=#{remark},icon=#{icon},updated_at=now() where no=#{no}")
    int updateDetails(Asset asset);

    List<Asset> list(@Param("status") String status,
                     @Param("category") String category,
                     @Param("keyword") String keyword);

    List<Asset> page(@Param("status") String status, @Param("category") String category,
                    @Param("keyword") String keyword, @Param("offset") long offset, @Param("size") int size);

    long count(@Param("status") String status, @Param("category") String category, @Param("keyword") String keyword);

    long countByStatus(@Param("status") String status);

    long countAll();
}
