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

    @Insert("insert into it_notify(to_user,title,content,type,ref_no) " +
            "values(#{toUser},#{title},#{content},#{type},#{refNo})")
    int insert(Notify n);

    @Select("select * from it_notify where to_user = #{user} order by id desc limit 50")
    List<Notify> listByUser(@Param("user") String user);

    @Select("select count(*) from it_notify where to_user = #{user} and is_read = 0")
    long countUnread(@Param("user") String user);

    @Update("update it_notify set is_read = 1 where id = #{id} and to_user = #{user}")
    int markRead(@Param("id") Long id, @Param("user") String user);

    @Update("update it_notify set is_read = 1 where to_user = #{user}")
    int markAllRead(@Param("user") String user);
}
