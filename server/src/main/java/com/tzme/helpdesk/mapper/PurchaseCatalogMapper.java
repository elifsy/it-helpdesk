package com.tzme.helpdesk.mapper;

import com.tzme.helpdesk.domain.PurchaseCatalog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PurchaseCatalogMapper {

    @Select("select * from it_purchase_catalog where (#{onlyEnabled} = 0 or enabled = 1) " +
            "order by sort asc, id asc")
    List<PurchaseCatalog> list(@Param("onlyEnabled") int onlyEnabled);

    @Select("select * from it_purchase_catalog where id = #{id}")
    PurchaseCatalog findById(@Param("id") Long id);

    @org.apache.ibatis.annotations.Insert("insert into it_purchase_catalog(name, category, spec, remark, icon, enabled, sort) " +
            "values(#{name}, #{category}, #{spec}, #{remark}, #{icon}, #{enabled}, #{sort})")
    @org.apache.ibatis.annotations.Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(PurchaseCatalog c);

    @org.apache.ibatis.annotations.Update("update it_purchase_catalog set name=#{name}, category=#{category}, " +
            "spec=#{spec}, remark=#{remark}, icon=#{icon}, enabled=#{enabled}, sort=#{sort} where id=#{id}")
    int update(PurchaseCatalog c);

    @org.apache.ibatis.annotations.Delete("delete from it_purchase_catalog where id=#{id}")
    int delete(@Param("id") Long id);
}
