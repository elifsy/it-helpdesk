package com.tzme.helpdesk.service;

import com.tzme.helpdesk.common.BizException;
import com.tzme.helpdesk.domain.KbArticle;
import com.tzme.helpdesk.mapper.KbArticleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class KbService {

    private final KbArticleMapper kbMapper;
    private final RuntimeSettingsService settings;

    public List<KbArticle> list(String keyword, String category) {
        return kbMapper.list(keyword, category);
    }

    public KbArticle detail(Long id, boolean countView) {
        KbArticle a = kbMapper.findById(id);
        if (a == null || !"PUBLISHED".equals(a.getStatus())) {
            throw new BizException(404, "知识条目不存在");
        }
        if (countView) kbMapper.incViews(id);
        a.setSourceTicketNo(null);
        return a;
    }

    /**
     * 提报前推荐：先按分类+关键词，再退化到分类，再退化到关键词，最后给热门。
     * 这是降低工单量的核心手段，不能因为没命中就返回空。
     */
    public List<KbArticle> recommend(String cat1, String desc) {
        if (!settings.get().getKbRecommendEnabled()) return List.of();
        String keyword = pickKeyword(desc);
        List<KbArticle> list = kbMapper.recommend(cat1, keyword);
        if (list.isEmpty()) {
            list = kbMapper.recommend(cat1, null);
        }
        if (list.isEmpty()) {
            list = kbMapper.recommend(null, keyword);
        }
        if (list.isEmpty()) {
            list = kbMapper.recommend(null, null);
        }
        return list;
    }

    public void vote(Long id, boolean helpful) {
        KbArticle article = kbMapper.findById(id);
        if (article == null || !"PUBLISHED".equals(article.getStatus())) throw new BizException(404, "知识条目不存在");
        kbMapper.incHelpful(id, helpful ? 1 : -1);
    }

    private String pickKeyword(String desc) {
        if (desc == null) {
            return null;
        }
        String d = desc.trim();
        if (d.isEmpty()) {
            return null;
        }
        String[] dict = {"WiFi", "无线", "网络", "VPN", "打印机", "脱机", "卡纸", "投屏", "会议室",
                "密码", "账号", "权限", "共享盘", "蓝屏", "驱动", "邮箱", "软件", "扫描"};
        for (String k : dict) {
            if (d.contains(k)) {
                return k;
            }
        }
        return d.length() > 6 ? d.substring(0, 6) : d;
    }
}
