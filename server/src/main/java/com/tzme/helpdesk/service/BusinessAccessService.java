package com.tzme.helpdesk.service;

import com.tzme.helpdesk.common.BizException;
import com.tzme.helpdesk.common.CurrentUser;
import com.tzme.helpdesk.domain.*;
import com.tzme.helpdesk.mapper.*;
import com.tzme.helpdesk.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 附件沿用所属业务的访问权限；写入时锁住业务行以串行校验附件数量。 */
@Service
@RequiredArgsConstructor
public class BusinessAccessService {
    private final TicketMapper tickets;
    private final AssetMapper assets;
    private final AssetRequestMapper requests;

    public void check(String type, String no, boolean write) {
        LoginUser u = CurrentUser.require();
        if (type == null || no == null || !no.matches("[\\p{L}\\p{N}_-]{1,32}")) {
            throw new BizException("业务类型或单号不合法");
        }
        boolean allowed;
        switch (type) {
            case "TICKET" -> {
                Ticket t = write ? tickets.findByNoForUpdate(no) : tickets.findByNo(no);
                if (t == null) throw new BizException(404, "工单不存在");
                boolean owner = u.id().equals(t.getCreatorId()) && u.hasPermission("ticket.view.own");
                boolean operator = u.hasPermission("ticket.handle") || u.hasPermission("ticket.accept") || u.hasPermission("ticket.dispatch");
                allowed = owner || (u.canManageTickets() && (!write || operator));
            }
            case "REQUEST" -> {
                AssetRequest r = write ? requests.findByNoForUpdate(no) : requests.findByNo(no);
                if (r == null) throw new BizException(404, "申请单不存在");
                allowed = (u.name().equals(r.getApplicant()) && u.hasPermission("request.view.own"))
                        || (u.hasPermission("request.view.all") && (!write || u.hasPermission("request.approve")));
            }
            case "ASSET" -> {
                Asset a = write ? assets.findByNoForUpdate(no) : assets.findByNo(no);
                if (a == null) throw new BizException(404, "资产不存在");
                allowed = u.hasPermission("asset.view") && (!write || u.hasPermission("asset.create") || u.hasPermission("request.approve")
                        || u.name().equals(a.getUserName()) || u.name().equals(a.getBorrower()));
            }
            default -> throw new BizException("不支持的附件业务类型");
        }
        if (!allowed) throw new BizException(403, "无权访问该业务的附件");
    }
}
