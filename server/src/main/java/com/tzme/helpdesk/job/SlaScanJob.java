package com.tzme.helpdesk.job;

import com.tzme.helpdesk.config.HelpdeskProperties;
import com.tzme.helpdesk.service.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 按运行配置扫描超时工单。
 * 启用 Redis 时用 SETNX 锁保证多实例只有一个节点执行；关闭时单机直接执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlaScanJob {

    private static final String LOCK_KEY = "helpdesk:job:sla:lock";

    private final TicketService ticketService;
    private final StringRedisTemplate redis;
    private final HelpdeskProperties props;
    private final com.tzme.helpdesk.service.RuntimeSettingsService settings;
    private final com.tzme.helpdesk.mapper.ManagementMapper management;

    public synchronized void scan() {
        if (!settings.get().getSlaScanEnabled()) return;
        if (!props.isRedisEnabled()) {
            runScan();
            return;
        }
        Boolean got = redis.opsForValue().setIfAbsent(LOCK_KEY, "1", Duration.ofMinutes(9));
        if (!Boolean.TRUE.equals(got)) {
            return;
        }
        try {
            runScan();
        } finally {
            redis.delete(LOCK_KEY);
        }
    }

    private void runScan() {
        try {
            int n = ticketService.scanTimeout();
            management.jobResult("sla", "SUCCESS", "扫描完成，提醒工单 " + n + " 张");
            if (n > 0) {
                log.info("sla scan: {} ticket reminders recorded", n);
            }
        } catch (Exception e) {
            management.jobResult("sla", "FAILED", "扫描失败，请查看服务器日志");
            log.warn("sla scan failed: {}", e.getMessage());
        }
    }
}
