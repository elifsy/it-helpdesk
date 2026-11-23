package com.tzme.helpdesk.service;

import com.tzme.helpdesk.domain.Notify;
import com.tzme.helpdesk.domain.User;
import com.tzme.helpdesk.mapper.NotifyMapper;
import com.tzme.helpdesk.mapper.UserMapper;
import com.tzme.helpdesk.security.LoginUser;
import com.tzme.helpdesk.security.Roles;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NotifyService {

    private final NotifyMapper notifyMapper;
    private final UserMapper userMapper;
    private final DingTalkService dingTalk;
    private final RolePermissionService permissions;

    public void notifyPermission(String permission,String title,String content,String type,String refNo) {
        for (User user:userMapper.listActive()) {
            if (permissions.resolve(user).hasPermission(permission)) send(user.getName(), title, content, type, refNo);
        }
    }

    public void notifyIncoming(String permission, String title, String content, String type, String refNo, String link) {
        List<User> recipients = userMapper.listActive().stream()
                .filter(user -> Roles.isItOrAdmin(user.getRole()) && permissions.resolve(user).hasPermission(permission))
                .toList();
        sendToAccounts(recipients, title, content, type, refNo, link);
    }

    public void send(String toUser, String title, String content, String type, String refNo) {
        if (toUser == null || toUser.isBlank()) {
            return;
        }
        save(toUser, null, title, content, type, refNo);

        if (dingTalk.enabled()) {
            User u = userMapper.findByName(toUser);
            if (u != null && u.getDingUserId() != null && !u.getDingUserId().isBlank()) {
                dingTalk.sendWorkNotify(u.getDingUserId(), title, content, null);
            }
        }
    }

    public void sendToAccounts(List<User> recipients, String title, String content, String type, String refNo, String link) {
        for (User recipient : recipients)
            save(recipient.getName(), recipient.getId(), title, content, type, refNo);
        if (!dingTalk.enabled()) return;

        String dingUserIds = recipients.stream().map(User::getDingUserId)
                .filter(id -> id != null && !id.isBlank()).distinct().collect(Collectors.joining(","));
        if (!dingUserIds.isEmpty()) sendAfterCommit(dingUserIds, title, content, link);
    }

    private void sendAfterCommit(String dingUserIds, String title, String content, String link) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    dingTalk.sendWorkNotify(dingUserIds, title, content, link);
                }
            });
        } else {
            dingTalk.sendWorkNotify(dingUserIds, title, content, link);
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
        List<User> users = userMapper.listByRole(role);
        for (User u : users) {
            send(u.getName(), title, content, type, refNo);
        }
    }

    public List<Notify> list(LoginUser user) {
        return notifyMapper.listByUser(user.id(), user.name());
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
