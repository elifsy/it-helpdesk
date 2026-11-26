package com.tzme.helpdesk.mapper;

import com.tzme.helpdesk.domain.Notify;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface NotifyMapper {

    String FOR_RECIPIENT = "(recipient_id = #{userId} or (recipient_id is null and to_user = #{user}))";
    String TICKET_SCOPE = "(type not in ('TICKET','SLA') or #{allTickets} = true or "
            + "(#{ownTickets} = true and exists (select 1 from it_ticket t where t.no=it_notify.ref_no and t.creator_id=#{userId})))";
    String VISIBLE = FOR_RECIPIENT + " and " + TICKET_SCOPE;

    @Insert("insert into it_notify(to_user,recipient_id,title,content,type,ref_no) " +
            "values(#{toUser},#{recipientId},#{title},#{content},#{type},#{refNo})")
    int insert(Notify n);

    @Select("select * from it_notify where " + VISIBLE + " order by id desc limit 50")
    List<Notify> listByUser(@Param("userId") Long userId, @Param("user") String user,
                           @Param("allTickets") boolean allTickets, @Param("ownTickets") boolean ownTickets);

    @Select("select count(*) from it_notify where " + VISIBLE + " and is_read = 0")
    long countUnread(@Param("userId") Long userId, @Param("user") String user,
                     @Param("allTickets") boolean allTickets, @Param("ownTickets") boolean ownTickets);

    @Update("update it_notify set is_read = 1 where id = #{id} and " + VISIBLE)
    int markRead(@Param("id") Long id, @Param("userId") Long userId, @Param("user") String user,
                 @Param("allTickets") boolean allTickets, @Param("ownTickets") boolean ownTickets);

    @Update("update it_notify set is_read = 1 where " + VISIBLE)
    int markAllRead(@Param("userId") Long userId, @Param("user") String user,
                    @Param("allTickets") boolean allTickets, @Param("ownTickets") boolean ownTickets);

    @Select("select creator_id from it_ticket where no = #{no}")
    Long ticketCreatorId(@Param("no") String no);
}
