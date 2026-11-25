package com.tzme.helpdesk.job;

import com.tzme.helpdesk.config.HelpdeskProperties;
import com.tzme.helpdesk.dto.JobSettings;
import com.tzme.helpdesk.service.RuntimeSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;

@Component
@Slf4j
@RequiredArgsConstructor
public class ScheduledJobs {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final TaskScheduler scheduler;
    private final RuntimeSettingsService settings;
    private final HelpdeskProperties props;
    private final SlaScanJob sla;
    private final ContactSyncJob contacts;
    private ScheduledFuture<?> slaTask;
    private ScheduledFuture<?> contactTask;
    private String slaCron;
    private String contactCron;
    private long revision = -1;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        configure(settings.jobSchedule());
    }

    @TransactionalEventListener
    public synchronized void configure(JobSettings config) {
        log.debug("Refreshing scheduled jobs from configuration revision {} (current revision {})", config.revision(), revision);
        if (config.revision() <= revision) return;
        String nextSla = activeCron(config.slaScanEnabled(), config.slaCron());
        String nextContact = activeCron(props.getDingtalk().isEnabled() && config.contactSyncEnabled(), config.contactCron());
        if (!Objects.equals(slaCron, nextSla)) {
            slaTask = reschedule(slaTask, nextSla, sla::scan);
            slaCron = nextSla;
        }
        if (!Objects.equals(contactCron, nextContact)) {
            contactTask = reschedule(contactTask, nextContact, contacts::dailySync);
            contactCron = nextContact;
        }
        revision = config.revision();
    }

    public static ZonedDateTime nextExecution(boolean enabled, String cron, ZonedDateTime now) {
        String active = activeCron(enabled, cron);
        return active == null ? null : CronExpression.parse(active).next(now.withZoneSameInstant(ZONE));
    }

    private static String activeCron(boolean enabled, String cron) {
        return enabled && !Scheduled.CRON_DISABLED.equals(cron) ? cron : null;
    }

    private ScheduledFuture<?> reschedule(ScheduledFuture<?> previous, String cron, Runnable task) {
        if (previous != null) previous.cancel(false);
        return cron == null ? null : scheduler.schedule(task, new CronTrigger(cron, ZONE));
    }
}

