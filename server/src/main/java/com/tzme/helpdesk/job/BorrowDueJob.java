package com.tzme.helpdesk.job;

import com.tzme.helpdesk.mapper.ManagementMapper;
import com.tzme.helpdesk.service.BorrowDueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BorrowDueJob {
    public static final String CRON = "0 0 9 * * ?";
    private static final int BATCH_SIZE = 100;
    private final BorrowDueService service;
    private final ManagementMapper management;

    @Scheduled(cron = CRON, zone = "Asia/Shanghai")
    public void scan() {
        try {
            int total = 0;
            int sent;
            do {
                sent = service.sendReminders(BATCH_SIZE);
                total += sent;
            } while (sent == BATCH_SIZE);
            management.jobResult("borrow-due", "SUCCESS", "提醒借用人 " + total + " 条");
        } catch (RuntimeException e) {
            management.jobResult("borrow-due", "FAILED", "借用提醒失败，请查看服务器日志");
            log.warn("Borrow due reminder scan failed", e);
        }
    }
}

