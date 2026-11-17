package com.tzme.helpdesk.mapper;

import com.tzme.helpdesk.domain.KbArticle;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface KbArticleMapper {
    int insertDraft(KbArticle article);
    KbArticle findBySource(@Param("no") String no);
    KbArticle lockById(@Param("id") Long id);
    List<KbArticle> managedList(@Param("keyword") String keyword, @Param("status") String status,
                              @Param("offset") int offset, @Param("size") int size);
    long managedCount(@Param("keyword") String keyword, @Param("status") String status);
    int updateArticle(KbArticle article);
    List<KbArticle> list(@Param("keyword") String keyword, @Param("category") String category);

    KbArticle findById(@Param("id") Long id);

    List<KbArticle> recommend(@Param("category") String category, @Param("keyword") String keyword);

    int incViews(@Param("id") Long id);

    int incHelpful(@Param("id") Long id, @Param("delta") int delta);
}
