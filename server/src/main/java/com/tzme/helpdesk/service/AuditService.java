package com.tzme.helpdesk.service;

import com.tzme.helpdesk.common.CurrentUser;
import com.tzme.helpdesk.mapper.ManagementMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.DependsOn;

@Service
@DependsOn("managementSchemaInitializer")
@RequiredArgsConstructor
public class AuditService {
    private final ManagementMapper mapper;
    public void record(String action, String target, String detail) {
        var user = CurrentUser.get();
        mapper.audit(user == null ? null : user.id(), user == null ? "系统任务" : user.name(),
                action, target, detail.length() > 2000 ? detail.substring(0,2000) : detail);
    }
}
