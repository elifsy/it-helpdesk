package com.tzme.helpdesk.job;

import com.tzme.helpdesk.config.HelpdeskProperties;
import com.tzme.helpdesk.service.DingTalkContactService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 按运行配置同步钉钉通讯录，离职当日停用并转派未结工单。
 * 未开启钉钉接入时直接跳过，本地演示不会触发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContactSyncJob {

    private final DingTalkContactService contactService;
    private final HelpdeskProperties props;
    private final com.tzme.helpdesk.service.RuntimeSettingsService settings;
    private final com.tzme.helpdesk.mapper.ManagementMapper management;

    public synchronized void dailySync() {
        if (!props.getDingtalk().isEnabled() || !settings.get().getContactSyncEnabled()) {
            return;
        }
        try {
            contactService.sync("live");
            management.jobResult("contacts","SUCCESS","通讯录同步完成");
        } catch (Exception e) {
            management.jobResult("contacts","FAILED","同步失败，请查看服务器日志");
            log.warn("contact sync failed: {}", e.getMessage());
        }
    }
}
