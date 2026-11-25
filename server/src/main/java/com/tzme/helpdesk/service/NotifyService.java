package com.tzme.helpdesk.service;

import com.tzme.helpdesk.domain.Notify;
import com.tzme.helpdesk.domain.User;
import com.tzme.helpdesk.dto.NotificationAction;
import com.tzme.helpdesk.mapper.NotifyMapper;
import com.tzme.helpdesk.mapper.UserMapper;
import com.tzme.helpdesk.security.LoginUser;
import com.tzme.helpdesk.security.Roles;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class NotifyService {
    static final String NEW_TICKET = "新工单待受理";
    static final String NEW_ASSET = "有新的资产申请";
    static final String NEW_PURCHASE = "有新的设备购入申请";
    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,31}");

    private final NotifyMapper notifyMapper;
    private final UserMapper userMapper;
    private final DingTalkService dingTalk;
    private final RolePermissionService permissions;
    private final RuntimeSettingsService settings;

    public void notifyPermission(String permission,String title,String content,String type,String refNo) {
        sendToAccounts(userMapper.listActive().stream()
                .filter(user -> permissions.resolve(user).hasPermission(permission)).toList(), title, content, type, refNo);
    }

    public void notifyIncoming(String permission, String title, String content, String type, String refNo) {
        List<User> recipients = userMapper.listActive().stream()
                .filter(user -> Roles.isItOrAdmin(user.getRole()) && permissions.resolve(user).hasPermission(permission))
                .toList();
        sendToAccounts(recipients, title, content, type, refNo);
    }

    public void send(Long recipientId, String toUser, String title, String content, String type, String refNo) {
        if (toUser == null || toUser.isBlank()) {
            return;
        }
        User recipient = recipientId == null ? userMapper.findByName(toUser) : userMapper.findById(recipientId);
        save(toUser, recipientId != null ? recipientId : recipient == null ? null : recipient.getId(),
                title, content, type, refNo);
        if (recipient != null && "ACTIVE".equals(recipient.getStatus()))
            deliver(List.of(recipient), title, content, type, refNo);
    }

    public void sendToAccounts(List<User> recipients, String title, String content, String type, String refNo) {
        for (User recipient : recipients)
            save(recipient.getName(), recipient.getId(), title, content, type, refNo);
        deliver(recipients, title, content, type, refNo);
    }

    private void deliver(List<User> recipients, String title, String content, String type, String refNo) {
        if (!dingTalk.enabled()) return;

        var groups = new LinkedHashMap<NotificationAction, Set<String>>();
        for (User recipient : recipients) {
            String id = recipient.getDingUserId();
            if (id == null || id.isBlank()) continue;
            var action = actionFor(permissions.resolve(recipient), type, refNo, title, content);
            groups.computeIfAbsent(action, ignored -> new LinkedHashSet<>()).add(id);
        }
        groups.forEach((action, ids) -> {
            String link = action == null ? null : workLink(action.path());
            sendAfterCommit(String.join(",", ids), title, content, link, action == null ? null : action.label());
        });
    }

    private String workLink(String path) {
        String baseUrl = settings.get().getQrcodeBaseUrl();
        if (baseUrl == null) return null;
        URI base;
        try {
            base = URI.create(baseUrl);
        } catch (IllegalArgumentException invalidUrl) {
            return null;
        }
        if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme())) || base.getHost() == null
                || base.getUserInfo() != null || base.getRawQuery() != null || base.getRawFragment() != null) return null;
        String entry = base.toASCIIString();
        if (!entry.endsWith("/")) entry += "/";
        String target = entry + "?notice=" + UriUtils.encode(path, StandardCharsets.UTF_8);
        return Objects.requireNonNullElse(dingTalk.workAppLink(target), target);
    }

    private void sendAfterCommit(String dingUserIds, String title, String content, String link, String button) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    dingTalk.sendWorkNotify(dingUserIds, title, content, link, button);
                }
            });
        } else {
            dingTalk.sendWorkNotify(dingUserIds, title, content, link, button);
        }
    }

    private void save(String toUser, Long recipientId, String title, String content, String type, String refNo) {
        Notify notification = new Notify();
        notification.setToUser(toUser);
        notification.setRecipientId(recipientId);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setType(type);
        notification.setRefNo(refNo);
        notifyMapper.insert(notification);
    }

    /** 按四种基础角色批量通知；业务通知优先按实际权限筛选。 */
    public void notifyRole(String role, String title, String content, String type, String refNo) {
        sendToAccounts(userMapper.listByRole(role), title, content, type, refNo);
    }

    public List<Notify> list(LoginUser user) {
        List<Notify> rows = notifyMapper.listByUser(user.id(), user.name());
        for (Notify row : rows) {
            row.setAction(actionFor(user, row.getType(), row.getRefNo(), row.getTitle(), row.getContent()));
        }
        return rows;
    }

    private NotificationAction actionFor(LoginUser user, String type, String refNo, String title, String content) {
        if (type == null || refNo == null || !REFERENCE.matcher(refNo).matches()) return null;
        boolean canSeeAllRequests = user.hasPermission("request.view.all");
        boolean canApprove = user.hasPermission("request.approve");
        boolean canViewPersonalRequests = user.hasPermission("asset.view")
                && (user.hasPermission("request.view.own") || canSeeAllRequests);
        return switch (type) {
            case "BORROW_DUE" -> user.hasPermission("asset.view") && user.hasPermission("request.create")
                    ? new NotificationAction("申请归还", "/assets?returnAsset=" + refNo) : null;
            case "TICKET", "SLA" -> {
                if (!user.hasPermission("ticket.view.all")
                        && (NEW_TICKET.equals(title) || !user.hasPermission("ticket.view.own"))) yield null;
                String label = "查看工单";
                if (NEW_TICKET.equals(title) && user.hasPermission("ticket.accept")) label = "去受理";
                if ("工单待确认".equals(title) && user.hasPermission("ticket.confirm")) label = "确认结果";
                if ("SLA".equals(type) && user.hasPermission("ticket.handle")) label = "处理超时工单";
                yield new NotificationAction(label, "/ticket/" + refNo);
            }
            case "ASSET" -> {
                if (NEW_ASSET.equals(title)) {
                    if (!canSeeAllRequests) yield null;
                    boolean handledInDingTalk = content != null && content.contains("已进入钉钉审批");
                    yield new NotificationAction(canApprove && !handledInDingTalk ? "去审批" : "查看资产申请",
                            "/admin/approvals?request=" + refNo);
                }
                yield canViewPersonalRequests ? new NotificationAction("查看资产申请", "/assets?request=" + refNo) : null;
            }
            case "PURCHASE" -> {
                if (NEW_PURCHASE.equals(title))
                    yield canApprove ? new NotificationAction("去审批", "/admin/purchase?request=" + refNo) : null;
                yield canViewPersonalRequests ? new NotificationAction("查看购入申请", "/assets?purchase=" + refNo) : null;
            }
            default -> null;
        };
    }

    public long unread(LoginUser user) {
        return notifyMapper.countUnread(user.id(), user.name());
    }

    public void markRead(Long id, LoginUser user) {
        notifyMapper.markRead(id, user.id(), user.name());
    }

    public void markAllRead(LoginUser user) {
        notifyMapper.markAllRead(user.id(), user.name());
    }
}
