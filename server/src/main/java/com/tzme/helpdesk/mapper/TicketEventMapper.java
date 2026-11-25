package com.tzme.helpdesk.mapper;

import com.tzme.helpdesk.domain.TicketEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TicketEventMapper {

    @Insert("insert into it_ticket_event(ticket_no,from_status,to_status,action,operator,operator_id,remark) " +
            "values(#{ticketNo},#{fromStatus},#{toStatus},#{action},#{operator},#{operatorId},#{remark})")
    int insert(TicketEvent e);

    @Select("select * from it_ticket_event where ticket_no = #{no} order by id asc")
    List<TicketEvent> listByNo(@Param("no") String no);

    @Select("select count(distinct ticket_no) from it_ticket_event where action='ACCEPT' and " +
            "(operator_id=#{id} or (operator_id is null and operator=#{name} " +
            "and (select count(*) from sys_user where name=#{name})=1))")
    long countAccepted(@Param("id") Long id, @Param("name") String name);
}
