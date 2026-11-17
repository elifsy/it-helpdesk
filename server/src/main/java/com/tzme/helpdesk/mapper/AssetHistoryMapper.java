package com.tzme.helpdesk.mapper;

import com.tzme.helpdesk.domain.AssetHistory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AssetHistoryMapper {

    @Insert("insert into it_asset_history(asset_no,action,detail,operator) " +
            "values(#{assetNo},#{action},#{detail},#{operator})")
    int insert(AssetHistory h);

    @Select("select * from it_asset_history where asset_no = #{no} order by id desc limit 20")
    List<AssetHistory> listByNo(@Param("no") String no);
}
