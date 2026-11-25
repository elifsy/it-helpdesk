package com.tzme.helpdesk.service;

import com.tzme.helpdesk.common.BizException;
import com.tzme.helpdesk.common.CurrentUser;
import com.tzme.helpdesk.domain.User;
import com.tzme.helpdesk.mapper.ManagementMapper;
import com.tzme.helpdesk.mapper.TicketEventMapper;
import com.tzme.helpdesk.mapper.UserMapper;
import com.tzme.helpdesk.security.Roles;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class AccountBadgeService {
    private static final String TITLE_SETTING = "badge-titles-enabled";
    private static final List<Title> TITLES = List.of(
            new Title(300, "legend", "运维宗师"),
            new Title(100, "sapphire", "排障专家"),
            new Title(30, "jade", "故障猎手"),
            new Title(0, "steel", "桌面卫士"));

    private final UserMapper users;
    private final TicketEventMapper events;
    private final ManagementMapper management;
    private final DingTalkService dingTalk;
    private final AuditService audit;
    private final ConcurrentHashMap<String, CachedAvatar> avatarCache = new ConcurrentHashMap<>();

    public enum AvatarSource { DINGTALK, VIRTUAL }
    public record Badge(AvatarSource avatarSource, String avatarUrl, Long acceptedTickets, String titleKey, String title,
                        String nextTitle, Long nextTitleAt) { }
    private record Title(long minimum, String key, String label) { }
    private record CachedAvatar(String url, Instant until) { }

    public Badge badge() {
        var actor = CurrentUser.require();
        User user = users.findById(actor.id());
        if (user == null || !"ACTIVE".equals(user.getStatus())) throw new BizException(403, "账号已停用");
        AvatarSource source = AvatarSource.VIRTUAL.name().equals(user.getBadgeAvatar()) ? AvatarSource.VIRTUAL : AvatarSource.DINGTALK;
        Long count = Roles.isItOrAdmin(user.getRole()) && titlesEnabled() ? events.countAccepted(user.getId(), user.getName()) : null;
        Title title = count == null ? null : TITLES.stream().filter(level -> count >= level.minimum()).findFirst().orElseThrow();
        int titleIndex = title == null ? -1 : TITLES.indexOf(title);
        Title next = titleIndex > 0 ? TITLES.get(titleIndex - 1) : null;
        String url = source == AvatarSource.DINGTALK ? avatar(user.getDingUserId()) : null;
        return new Badge(source, url, count, title == null ? null : title.key(), title == null ? null : title.label(),
                next == null ? null : next.label(), next == null ? null : next.minimum());
    }

    public Badge saveAvatar(AvatarSource source) {
        users.updateBadgeAvatar(CurrentUser.require().id(), source.name());
        return badge();
    }

    public boolean titleSettings() {
        requireSuperAdmin();
        return titlesEnabled();
    }

    @Transactional
    public boolean setTitlesEnabled(boolean enabled) {
        requireSuperAdmin();
        management.lockSetting("management-lock");
        User user = users.findById(CurrentUser.require().id());
        if (user == null || !"ACTIVE".equals(user.getStatus()) || !Roles.SUPER_ADMIN.equals(user.getRole())) {
            throw new BizException(403, "仅超级管理员可以管理工牌称号");
        }
        if (enabled != titlesEnabled()) {
            management.createSetting(TITLE_SETTING, Boolean.toString(enabled));
            management.updateSetting(TITLE_SETTING, Boolean.toString(enabled));
            audit.record("BADGE_TITLES_UPDATE", TITLE_SETTING, enabled ? "开启工牌称号" : "关闭工牌称号");
        }
        return enabled;
    }

    private boolean titlesEnabled() {
        return !"false".equals(management.setting(TITLE_SETTING));
    }

    private void requireSuperAdmin() {
        if (!CurrentUser.require().hasRole(Roles.SUPER_ADMIN)) throw new BizException(403, "仅超级管理员可以管理工牌称号");
    }

    private String avatar(String dingUserId) {
        if (!dingTalk.enabled() || dingUserId == null || dingUserId.isBlank()) return null;
        Instant now = Instant.now();
        return avatarCache.compute(dingUserId, (id, cached) -> {
            if (cached != null && now.isBefore(cached.until())) return cached;
            String url = null;
            try {
                String address = dingTalk.getUserDetail(id).avatar();
                if (address != null && !address.isBlank()) {
                    URI candidate = URI.create(address);
                    if ("https".equalsIgnoreCase(candidate.getScheme()) && candidate.getHost() != null && candidate.getUserInfo() == null)
                        url = candidate.toASCIIString();
                }
            } catch (BizException | IllegalArgumentException ignored) {
                // 头像接口暂不可用时，工牌仍可用姓名首字显示。
            }
            return new CachedAvatar(url, now.plusSeconds(url == null ? 60 : 900));
        }).url();
    }
}
