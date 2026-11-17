package com.tzme.helpdesk.service;

import com.tzme.helpdesk.common.*;
import com.tzme.helpdesk.domain.*;
import com.tzme.helpdesk.dto.KbSaveReq;
import com.tzme.helpdesk.mapper.KbArticleMapper;
import com.tzme.helpdesk.security.Permissions;
import com.tzme.helpdesk.support.TicketStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class KnowledgeManagementService {
    private final KbArticleMapper articles;
    private final AuditService audit;

    /** 调用方必须在同一事务中锁住来源工单；唯一索引同时保护重复来源。 */
    @Transactional
    public KbArticle captureClosedTicket(Ticket ticket) {
        if (!TicketStatus.CLOSED.equals(ticket.getStatus())) throw new BizException("仅已关闭工单可以沉淀知识");
        KbArticle existing = articles.findBySource(ticket.getNo());
        if (existing != null) return existing;
        if (ticket.getSolutionNote() == null || ticket.getSolutionNote().isBlank())
            throw new BizException("该工单没有处理结论，无法沉淀知识");
        KbArticle article = new KbArticle();
        article.setTitle(ticket.getTitle().trim());
        article.setCategory(ticket.getCat1());
        article.setSummary(ticket.getSolutionNote().trim());
        article.setContent("问题现象\n" + ticket.getDescription().trim() + "\n\n处理结论与步骤\n" + ticket.getSolutionNote().trim());
        article.setSourceTicketNo(ticket.getNo());
        articles.insertDraft(article);
        audit.record("KB_CAPTURE", String.valueOf(article.getId()), "由已关闭工单 " + ticket.getNo() + " 生成知识草稿");
        return articles.findById(article.getId());
    }

    public PageResult<KbArticle> list(String keyword, String status, int page, int size) {
        Permissions.require("kb.manage");
        if (status != null && !status.isBlank() && !Set.of("DRAFT", "PUBLISHED", "ARCHIVED").contains(status))
            throw new BizException("知识状态不正确");
        int p = Math.max(1, page), s = Math.max(1, Math.min(100, size));
        return new PageResult<>(articles.managedList(keyword, status, (p - 1) * s, s), articles.managedCount(keyword, status), p, s);
    }

    public KbArticle detail(Long id) {
        Permissions.require("kb.manage");
        KbArticle article = articles.findById(id);
        if (article == null) throw new BizException(404, "知识条目不存在");
        return article;
    }

    @Transactional
    public KbArticle save(Long id, KbSaveReq input) {
        Permissions.require("kb.manage");
        KbArticle article = lock(id, input.getRevision());
        article.setTitle(input.getTitle().trim());
        article.setCategory(input.getCategory().trim());
        article.setSummary(input.getSummary().trim());
        article.setContent(input.getContent().trim());
        // 每次修改均需重新发布，避免整理中的内容直接进入用户推荐。
        article.setStatus("DRAFT");
        persist(article, "KB_SAVE_DRAFT", "保存知识草稿");
        return articles.findById(id);
    }

    @Transactional
    public KbArticle changeStatus(Long id, Integer revision, String action) {
        Permissions.require("kb.manage");
        KbArticle article = lock(id, revision);
        if ("publish".equals(action)) {
            Permissions.require("kb.publish");
            if (!"DRAFT".equals(article.getStatus())) throw new BizException("仅草稿可以发布，请先整理保存");
            if (blank(article.getTitle()) || blank(article.getCategory()) || blank(article.getSummary()) || blank(article.getContent()))
                throw new BizException("请先补全标题、分类、摘要和处理步骤");
            article.setStatus("PUBLISHED");
        } else if ("withdraw".equals(action)) {
            Permissions.require("kb.publish");
            if (!"PUBLISHED".equals(article.getStatus())) throw new BizException("仅已发布知识可以撤回");
            article.setStatus("DRAFT");
        } else if ("archive".equals(action)) {
            if ("PUBLISHED".equals(article.getStatus())) Permissions.require("kb.publish");
            if ("ARCHIVED".equals(article.getStatus())) throw new BizException("该知识已归档");
            article.setStatus("ARCHIVED");
        } else throw new BizException("不支持的知识操作");
        persist(article, "KB_" + action.toUpperCase(java.util.Locale.ROOT), "知识状态变更为 " + article.getStatus());
        return articles.findById(id);
    }

    private KbArticle lock(Long id, Integer revision) {
        KbArticle article = articles.lockById(id);
        if (article == null) throw new BizException(404, "知识条目不存在");
        if (!article.getRevision().equals(revision)) throw new BizException(409, "知识已被其他人修改，请重新打开后再操作");
        return article;
    }
    private void persist(KbArticle article, String action, String detail) {
        if (articles.updateArticle(article) != 1) throw new BizException(409, "知识已更新，请重新加载");
        audit.record(action, String.valueOf(article.getId()), detail);
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }
}
