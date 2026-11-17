package com.tzme.helpdesk.mapper;

import com.tzme.helpdesk.domain.Attachment;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AttachmentMapper {
    @Select("select count(*) from it_attachment where biz_type=#{bizType} and biz_no=#{bizNo}")
    long countByBusiness(@Param("bizType") String bizType, @Param("bizNo") String bizNo);

    @Select("select count(*) as fileCount, coalesce(sum(file_size),0) as totalBytes from it_attachment")
    java.util.Map<String, Object> storageSummary();

    @Insert("insert into it_attachment(biz_type,biz_no,file_name,stored_name,content_type,file_size,uploader) "
            + "values(#{bizType},#{bizNo},#{fileName},#{storedName},#{contentType},#{fileSize},#{uploader})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Attachment a);

    @Select("select * from it_attachment where id = #{id}")
    Attachment findById(@Param("id") Long id);

    @Select("select * from it_attachment where stored_name = #{storedName}")
    Attachment findByStored(@Param("storedName") String storedName);

    @Select("select * from it_attachment where biz_type = #{bizType} and biz_no = #{bizNo} order by id asc")
    List<Attachment> list(@Param("bizType") String bizType, @Param("bizNo") String bizNo);

    @Select("select count(*) from it_attachment where biz_no = #{bizNo}")
    long countByBizNo(@Param("bizNo") String bizNo);

    @Delete("delete from it_attachment where id = #{id}")
    int delete(@Param("id") Long id);
}
