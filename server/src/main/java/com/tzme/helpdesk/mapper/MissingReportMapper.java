package com.tzme.helpdesk.mapper;

import com.tzme.helpdesk.domain.MissingReport;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface MissingReportMapper {
    @Insert("insert into it_missing_report(no,requester_id,requester_name,recorded_by,title,description,channel,completed_at) "
            + "values(#{no},#{requesterId},#{requesterName},#{recordedBy},#{title},#{description},#{channel},#{completedAt})")
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

    @Select("select count(*) from it_missing_report where requester_id=#{id} and status='PENDING'")
    long pendingCount(@Param("id") Long id);

    @Update("update it_missing_report set status='SUBMITTED',ticket_no=#{ticketNo} where no=#{no} and status='PENDING'")
    int submit(@Param("no") String no, @Param("ticketNo") String ticketNo);

    @Update("update it_missing_report set status='CANCELLED',cancel_reason=#{reason} where no=#{no} and status='PENDING'")
    int cancel(@Param("no") String no, @Param("reason") String reason);
}
