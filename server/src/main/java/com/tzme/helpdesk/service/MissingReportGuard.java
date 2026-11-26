package com.tzme.helpdesk.service;

import com.tzme.helpdesk.common.BizException;
import com.tzme.helpdesk.domain.PendingReportCounts;
import com.tzme.helpdesk.domain.User;
import com.tzme.helpdesk.mapper.MissingReportMapper;
import com.tzme.helpdesk.mapper.UserMapper;
import com.tzme.helpdesk.security.LoginUser;
import com.tzme.helpdesk.security.Roles;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class MissingReportGuard {
    public static final int LIMIT = 3;
    private final MissingReportMapper reports;
    private final UserMapper users;

    public User lockRequester(Long id) {
        User user = users.findByIdForUpdate(id);
        if (user == null) throw new BizException(404, "需求人不存在");
        return user;
    }

    static LocalDate staffCountDate(LoginUser user) {
        return Roles.isItOrAdmin(user.role()) ? BorrowDueService.today() : null;
    }

    public PendingReportCounts pendingByType(Long id) {
        return reports.pendingCounts(id, BorrowDueService.today());
    }

    boolean hasPendingReturn(Long id, String assetNo) {
        return reports.pendingReturnAssets(id).contains(assetNo);
    }

    public void requireCanSubmit(Long id) {
        lockRequester(id);
        requireWithinLimit(id);
    }

    void requireWithinLimit(Long id) {
        long pending = pendingByType(id).total();
        if (pending > LIMIT) throw new BizException(409, "还有 " + pending + " 项待处理，请先补交事项或归还逾期设备");
    }

    public void requireCanBrowsePersonalAssets(Long id) {
        if (pendingByType(id).total() > LIMIT) {
            throw new BizException(409, "请先补交事项或归还逾期设备，再使用资产服务");
        }
    }
}
