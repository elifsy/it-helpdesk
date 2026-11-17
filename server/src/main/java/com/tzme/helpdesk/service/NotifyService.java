package com.tzme.helpdesk.service;

import com.tzme.helpdesk.domain.Notify;
import com.tzme.helpdesk.domain.User;
import com.tzme.helpdesk.mapper.NotifyMapper;
import com.tzme.helpdesk.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyService {

    private final NotifyMapper notifyMapper;
    private final UserMapper userMapper;
    private final DingTalkService dingTalk;
    private final RolePermissionService permissions;

    public void notifyPermission(String permission,String title,String content,String type,String refNo) {
        for (User user:userMapper.listActive()) {
            if (permissions.resolve(user).hasPermission(permission)) send(user.getName(),title,content,type,refNo);
        }
    }

    public void send(String toUser, String title, String content, String type, String refNo) {
        if (toUser == null || toUser.isBlank()) {
            return;
        }
        Notify n = new Notify();
        n.setToUser(toUser);
        n.setTitle(title);
        n.setContent(content);
        n.setType(type);
        n.setRefNo(refNo);
        notifyMapper.insert(n);

        if (dingTalk.enabled()) {
            User u = userMapper.findByName(toUser);
            if (u != null && u.getDingUserId() != null && !u.getDingUserId().isBlank()) {
                dingTalk.sendWorkNotify(u.getDingUserId(), title, content, null);
            }
        }
    }

    /** 按四种基础角色批量通知；业务通知优先按实际权限筛选。 */
    public void notifyRole(String role, String title, String content, String type, String refNo) {
        List<User> users = userMapper.listByRole(role);
        for (User u : users) {
            send(u.getName(), title, content, type, refNo);
        }
    }

    public List<Notify> list(String user) {
        return notifyMapper.listByUser(user);
    }

    public long unread(String user) {
        return notifyMapper.countUnread(user);
    }

    public void markRead(Long id, String user) {
        notifyMapper.markRead(id, user);
    }

    public void markAllRead(String user) {
        notifyMapper.markAllRead(user);
    }
}
