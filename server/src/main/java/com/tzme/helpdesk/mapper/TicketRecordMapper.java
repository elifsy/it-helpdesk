package com.tzme.helpdesk.mapper;

import com.tzme.helpdesk.domain.TicketRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TicketRecordMapper {

    @Insert("insert into it_ticket_record(ticket_no,kind,content,cost_min,operator) " +
            "values(#{ticketNo},#{kind},#{content},#{costMin},#{operator})")
    int insert(TicketRecord r);

    @Select("select * from it_ticket_record where ticket_no = #{no} order by id asc")
    List<TicketRecord> listByNo(@Param("no") String no);
}
