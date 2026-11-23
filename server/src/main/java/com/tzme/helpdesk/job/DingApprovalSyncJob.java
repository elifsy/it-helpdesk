package com.tzme.helpdesk.job;

import com.tzme.helpdesk.config.HelpdeskProperties;
import com.tzme.helpdesk.dto.RuntimeSettings;
import com.tzme.helpdesk.service.DingApprovalSyncService;
import com.tzme.helpdesk.service.RuntimeSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 钉钉审批结果轮询：默认每分钟扫一次，按配置的间隔跳过多余轮次。
 * 只查询「已发起钉钉审批、仍在 PENDING」的申请，量很小，不会打满接口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DingApprovalSyncJob {

    private static final String LOCK_KEY = "helpdesk:job:ding-approval:lock";

    private final DingApprovalSyncService syncService;
    private final RuntimeSettingsService settings;
    private final StringRedisTemplate redis;
    private final HelpdeskProperties props;

    private volatile long lastRunAt = 0L;

    @Scheduled(cron = "0 * * * * ?")
    public void tick() {
        RuntimeSettings s = settings.get();
        if (!Boolean.TRUE.equals(s.getApprovalEnabled())) return;
        int interval = s.getApprovalPollMinutes() == null ? 2 : s.getApprovalPollMinutes();
        long now = System.currentTimeMillis();
        if (now - lastRunAt < interval * 60_000L) return;

        if (props.isRedisEnabled()) {
            Boolean got = redis.opsForValue().setIfAbsent(LOCK_KEY, "1", Duration.ofSeconds(50));
            if (!Boolean.TRUE.equals(got)) return;
            try {
                run();
            } finally {
                redis.delete(LOCK_KEY);
            }
        } else {
            run();
        }
    }

    private void run() {
        lastRunAt = System.currentTimeMillis();
        try {
            int n = syncService.syncAll();
            if (n > 0) log.info("ding approval sync: {} handled", n);
        } catch (Exception e) {
            log.warn("ding approval sync failed: {}", e.getMessage());
        }
    }
}
