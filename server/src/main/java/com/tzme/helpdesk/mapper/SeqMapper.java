package com.tzme.helpdesk.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 无 Redis 时按数据库当天最大单号取号。
 */
@Mapper
public interface SeqMapper {

    @Select("select max(no) from it_ticket where no like concat('IT-', #{day}, '-%')")
    String maxTicketNo(@Param("day") String day);

    @Select("select max(no) from it_asset_request where no like concat('AS-', #{day}, '-%')")
    String maxAssetRequestNo(@Param("day") String day);
}
