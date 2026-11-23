package com.tzme.helpdesk.mapper;

import com.tzme.helpdesk.domain.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UserMapper {
    @org.apache.ibatis.annotations.Select("select * from sys_user where status='ACTIVE' order by id")
    List<User> listActive();
    @org.apache.ibatis.annotations.Select("select name from sys_user where status='ACTIVE'")
    List<String> listActiveNames();
    @org.apache.ibatis.annotations.Select("select * from sys_user where id=#{id} for update")
    User findByIdForUpdate(@Param("id") Long id);
    @org.apache.ibatis.annotations.Select("select * from sys_user where (#{keyword}='' or name like concat('%',#{keyword},'%') or employee_no like concat('%',#{keyword},'%')) and (#{status}='' or status=#{status}) order by id limit #{offset},#{size}")
    List<User> adminList(@Param("keyword") String keyword, @Param("status") String status, @Param("offset") int offset, @Param("size") int size);
    @org.apache.ibatis.annotations.Select("select count(*) from sys_user where (#{keyword}='' or name like concat('%',#{keyword},'%') or employee_no like concat('%',#{keyword},'%')) and (#{status}='' or status=#{status})")
    long adminCount(@Param("keyword") String keyword, @Param("status") String status);
    @org.apache.ibatis.annotations.Update("update sys_user set role=#{role},status=#{status},dept_id=#{deptId},dept_name=#{deptName},skill=#{skill},location=#{location},updated_at=now() where id=#{id}")
    int adminUpdate(User user);
    User findById(@Param("id") Long id);

    User findByName(@Param("name") String name);

    User findByDingId(@Param("dingUserId") String dingUserId);

    int insert(User user);

    List<User> listEngineers();

    List<User> listByRole(@Param("role") String role);

    /**
     * 通讯录同步用：按钉钉 userid 新增或更新。
     * 只覆盖姓名/部门/联系方式与在职状态，role、skill、location 由本地维护，不能被同步冲掉。
     */
    @org.apache.ibatis.annotations.Insert(
            "insert into sys_user(ding_user_id,name,employee_no,dept_id,dept_name,mobile,email,role,status) "
            + "values(#{dingUserId},#{name},#{employeeNo},#{deptId},#{deptName},#{mobile},#{email},'USER','ACTIVE') "
            + "on duplicate key update name=values(name), dept_id=values(dept_id), dept_name=values(dept_name), "
            + "mobile=values(mobile), email=values(email), status='ACTIVE', updated_at=now()")
    int upsertByDing(User user);

    @org.apache.ibatis.annotations.Select(
            "select * from sys_user where ding_user_id is not null and ding_user_id <> '' and status='ACTIVE'")
    java.util.List<User> listActiveWithDing();

    @org.apache.ibatis.annotations.Update("update sys_user set status='DISABLED', updated_at=now() where id=#{id}")
    int disable(@Param("id") Long id);
}
