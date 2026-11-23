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

    @Insert("insert into it_notify(to_user,recipient_id,title,content,type,ref_no) " +
            "values(#{toUser},#{recipientId},#{title},#{content},#{type},#{refNo})")
    int insert(Notify n);

    @Select("select * from it_notify where " + FOR_RECIPIENT + " order by id desc limit 50")
    List<Notify> listByUser(@Param("userId") Long userId, @Param("user") String user);

    @Select("select count(*) from it_notify where " + FOR_RECIPIENT + " and is_read = 0")
    long countUnread(@Param("userId") Long userId, @Param("user") String user);

    @Update("update it_notify set is_read = 1 where id = #{id} and " + FOR_RECIPIENT)
    int markRead(@Param("id") Long id, @Param("userId") Long userId, @Param("user") String user);

    @Update("update it_notify set is_read = 1 where " + FOR_RECIPIENT)
    int markAllRead(@Param("userId") Long userId, @Param("user") String user);
}
