package com.tzme.helpdesk.mapper;

import org.apache.ibatis.annotations.*;
import java.util.*;

@Mapper
public interface ManagementMapper {
    @Select("select setting_value from sys_setting where setting_key=#{key}")
    String setting(@Param("key") String key);
    @Select("select setting_key from sys_setting where setting_key like concat(#{prefix}, '%')")
    List<String> settingKeysWithPrefix(@Param("prefix") String prefix);
    @Insert("insert ignore into sys_setting(setting_key,setting_value) values(#{key},#{value})")
    int createSetting(@Param("key") String key, @Param("value") String value);
    @Select("select setting_value from sys_setting where setting_key=#{key} for update")
    String lockSetting(@Param("key") String key);
    @Update("update sys_setting set setting_value=#{value},updated_at=now() where setting_key=#{key}")
    int updateSetting(@Param("key") String key, @Param("value") String value);
    @Insert("insert into sys_audit_log(actor_id,actor_name,action,target,detail) values(#{actorId},#{actorName},#{action},#{target},#{detail})")
    int audit(@Param("actorId") Long actorId, @Param("actorName") String actorName, @Param("action") String action,
              @Param("target") String target, @Param("detail") String detail);
    @Select("select * from sys_audit_log where (#{keyword}='' or actor_name like concat('%',#{keyword},'%') or action like concat('%',#{keyword},'%') or target like concat('%',#{keyword},'%')) order by id desc limit #{offset},#{size}")
    List<Map<String,Object>> audits(@Param("keyword") String keyword, @Param("offset") int offset, @Param("size") int size);
    @Select("select count(*) from sys_audit_log where (#{keyword}='' or actor_name like concat('%',#{keyword},'%') or action like concat('%',#{keyword},'%') or target like concat('%',#{keyword},'%'))")
    long auditCount(@Param("keyword") String keyword);
    @Insert("insert into sys_job_run(job_key,status,message,finished_at) values(#{key},#{status},#{message},now()) on duplicate key update status=values(status),message=values(message),finished_at=now()")
    int jobResult(@Param("key") String key, @Param("status") String status, @Param("message") String message);
    @Select("select * from sys_job_run order by job_key")
    List<Map<String,Object>> jobs();
}
