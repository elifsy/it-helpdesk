package com.tzme.helpdesk.mapper;

import com.tzme.helpdesk.domain.TicketEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TicketEventMapper {

    @Insert("insert into it_ticket_event(ticket_no,from_status,to_status,action,operator,remark) " +
            "values(#{ticketNo},#{fromStatus},#{toStatus},#{action},#{operator},#{remark})")
    int insert(TicketEvent e);

    @Select("select * from it_ticket_event where ticket_no = #{no} order by id asc")
    List<TicketEvent> listByNo(@Param("no") String no);
}
