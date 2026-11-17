package com.tzme.helpdesk.mapper;
import com.tzme.helpdesk.domain.AssetCategory;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper
public interface AssetCategoryMapper {
    @Select("select c.id,c.name,c.sort,count(a.id) as asset_count from it_asset_category c left join it_asset a on a.category=c.name group by c.id,c.name,c.sort order by c.sort,c.id")
    List<AssetCategory> list();
    @Select("select * from it_asset_category where id=#{id}") AssetCategory findById(Long id);
    @Select("select * from it_asset_category where name=#{name}") AssetCategory findByName(String name);
    @Insert("insert into it_asset_category(name,sort) values(#{name},#{sort})")
    @Options(useGeneratedKeys=true,keyProperty="id") int insert(AssetCategory category);
    @Update("update it_asset_category set name=#{name},sort=#{sort} where id=#{id}") int update(AssetCategory category);
    @Delete("delete from it_asset_category where id=#{id}") int delete(Long id);
    @Select("select count(*) from it_asset where category=#{name}") long countAssets(String name);
    @Update("update it_asset set category=#{newName},updated_at=now() where category=#{oldName}")
    int renameAssets(@Param("oldName") String oldName,@Param("newName") String newName);
}
