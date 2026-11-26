package com.tzme.helpdesk.mapper;

import com.tzme.helpdesk.domain.MissingReport;
import com.tzme.helpdesk.domain.PendingReportCounts;
import org.apache.ibatis.annotations.*;
import java.time.LocalDate;
import java.util.List;

@Mapper
public interface MissingReportMapper {
    @Insert("insert into it_missing_report(no,requester_id,requester_name,recorded_by,title,description,channel,completed_at,"
            + "business_type,asset_no,new_asset_no,expect_return) "
            + "values(#{no},#{requesterId},#{requesterName},#{recordedBy},#{title},#{description},#{channel},#{completedAt},"
            + "#{businessType},#{assetNo},#{newAssetNo},#{expectReturn})")
    int insert(MissingReport report);

    @Select("select * from it_missing_report where no=#{no}")
    MissingReport find(@Param("no") String no);

    @Select("select * from it_missing_report where no=#{no} for update")
    MissingReport lock(@Param("no") String no);

    @Select("select * from it_missing_report where requester_id=#{id} and status<>'CANCELLED' order by created_at desc,no desc")
    List<MissingReport> mine(@Param("id") Long id);

    @Select("select * from it_missing_report order by case when status='PENDING' then 0 else 1 end,created_at desc,no desc limit #{offset},#{size}")
    List<MissingReport> list(@Param("offset") long offset, @Param("size") int size);

    @Select("select count(*) from it_missing_report")
    long total();

    PendingReportCounts pendingCounts(@Param("id") Long id, @Param("supplementDate") LocalDate supplementDate);

    @Select("select asset_no from it_missing_report where requester_id=#{id} and status='PENDING' and business_type='RETURN'")
    List<String> pendingReturnAssets(@Param("id") Long id);

    @Update("update it_missing_report set status='SUBMITTED',ticket_no=#{ticketNo} where no=#{no} and status='PENDING'")
    int submit(@Param("no") String no, @Param("ticketNo") String ticketNo);

    @Update("update it_missing_report set status='SUBMITTED',asset_request_no=#{requestNo} where no=#{no} and status='PENDING'")
    int submitAsset(@Param("no") String no, @Param("requestNo") String requestNo);

    @Select("select count(*) from it_missing_report where requester_id=#{id} and status='PENDING' "
            + "and (asset_no in (#{assetNo},#{newAssetNo}) or new_asset_no in (#{assetNo},#{newAssetNo}))")
    int pendingForAssets(@Param("id") Long id, @Param("assetNo") String assetNo, @Param("newAssetNo") String newAssetNo);

    @Update("update it_missing_report set status='CANCELLED',cancel_reason=#{reason} where no=#{no} and status='PENDING'")
    int cancel(@Param("no") String no, @Param("reason") String reason);
}
