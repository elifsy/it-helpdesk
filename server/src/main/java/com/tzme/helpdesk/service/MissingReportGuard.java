package com.tzme.helpdesk.service;

import com.tzme.helpdesk.common.BizException;
import com.tzme.helpdesk.domain.User;
import com.tzme.helpdesk.mapper.MissingReportMapper;
import com.tzme.helpdesk.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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

    public long pending(Long id) {
        return reports.pendingCount(id);
    }

    public void requireCanSubmit(Long id) {
        lockRequester(id);
        long pending = pending(id);
        if (pending > LIMIT) throw new BizException(409, "还有 " + pending + " 条已完成事项待补报，请先补报再发起报修或资产申请");
    }

    public void requireCanBrowsePersonalAssets(Long id) {
        if (pending(id) > LIMIT) throw new BizException(409, "请先补报已完成事项，再使用资产服务");
    }
}
