package com.tzme.helpdesk.mapper;

import com.tzme.helpdesk.domain.Dept;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface DeptMapper {
    @Select("select * from sys_dept where id=#{id}")
    Dept findById(@Param("id") Long id);
    @Update("update sys_dept set name=#{name},parent_id=#{parentId},sort=#{sort} where id=#{id}")
    int adminUpdate(Dept d);
    @Update("update sys_user set dept_name=#{name} where dept_id=#{id}")
    int renameUsers(@Param("id") Long id, @Param("name") String name);
    @Select("select (select count(*) from sys_dept where parent_id=#{id}) + (select count(*) from sys_user where dept_id=#{id})")
    long references(@Param("id") Long id);
    @org.apache.ibatis.annotations.Delete("delete from sys_dept where id=#{id}")
    int delete(@Param("id") Long id);

    @Select("select * from sys_dept order by id")
    List<Dept> listAll();

    @Select("select * from sys_dept where ding_dept_id = #{dingDeptId} limit 1")
    Dept findByDingId(@Param("dingDeptId") String dingDeptId);

    @Select("select * from sys_dept where name = #{name} order by id limit 1")
    Dept findByName(@Param("name") String name);

    @Insert("insert into sys_dept(ding_dept_id,name,parent_id,sort) values(#{dingDeptId},#{name},#{parentId},#{sort})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Dept d);

    @Update("update sys_dept set name = #{name}, parent_id = #{parentId} where id = #{id}")
    int update(Dept d);

    /** 本地已有同名部门（来自种子数据）时，把钉钉 id 绑上去，避免同步出重复部门 */
    @Update("update sys_dept set ding_dept_id = #{dingDeptId} where id = #{id}")
    int bindDingId(@Param("id") Long id, @Param("dingDeptId") String dingDeptId);
}
