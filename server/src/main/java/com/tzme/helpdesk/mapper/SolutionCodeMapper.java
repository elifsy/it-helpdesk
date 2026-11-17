package com.tzme.helpdesk.mapper;

import com.tzme.helpdesk.domain.SolutionCode;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SolutionCodeMapper {
    @org.apache.ibatis.annotations.Select("select * from it_solution_code where id=#{id}")
    SolutionCode findById(Long id);
    @org.apache.ibatis.annotations.Insert("insert into it_solution_code(category,code,name,sort) values(#{category},#{code},#{name},#{sort})")
    @org.apache.ibatis.annotations.Options(useGeneratedKeys=true,keyProperty="id")
    int insert(SolutionCode code);
    @org.apache.ibatis.annotations.Update("update it_solution_code set category=#{category},name=#{name},sort=#{sort} where id=#{id}")
    int update(SolutionCode code);
    @org.apache.ibatis.annotations.Delete("delete from it_solution_code where id=#{id}")
    int delete(Long id);
    @org.apache.ibatis.annotations.Select("select count(*) from it_ticket where solution_code=#{code}")
    long references(String code);
    List<SolutionCode> listAll();
}
