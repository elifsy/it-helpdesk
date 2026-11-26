package com.tzme.helpdesk.service;

import com.tzme.helpdesk.common.CurrentUser;
import com.tzme.helpdesk.domain.Asset;
import com.tzme.helpdesk.job.ScheduledJobs;
import com.tzme.helpdesk.mapper.AssetMapper;
import com.tzme.helpdesk.mapper.MissingReportMapper;
import com.tzme.helpdesk.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BorrowDueService {
    public static final int ADVANCE_DAYS = 3;
    private final AssetMapper assets;
    private final UserMapper users;
    private final NotifyService notify;
    private final MissingReportMapper reports;

    public record DueAssets(List<Asset> upcoming, List<Asset> overdue, int advanceDays,
                            List<String> returnSupplementAssets) { }

    static LocalDate today() {
        return LocalDate.now(ScheduledJobs.ZONE);
    }

    static boolean isOverdue(Asset asset, Long borrowerId, LocalDate today) {
        return "借出中".equals(asset.getStatus()) && borrowerId.equals(asset.getBorrowerId())
                && asset.getBorrowDue() != null && asset.getBorrowDue().isBefore(today);
    }

    public DueAssets mine() {
        LocalDate today = today();
        Long userId = CurrentUser.require().id();
        var upcoming = new ArrayList<Asset>();
        var overdue = new ArrayList<Asset>();
        for (Asset asset : assets.borrowDueForUser(userId, today.plusDays(ADVANCE_DAYS))) {
            (asset.getBorrowDue().isBefore(today) ? overdue : upcoming).add(asset);
        }
        return new DueAssets(upcoming, overdue, ADVANCE_DAYS, reports.pendingReturnAssets(userId));
    }

    @Transactional
    public int sendReminders(int limit) {
        LocalDateTime now = LocalDateTime.now(ScheduledJobs.ZONE).withNano(0);
        LocalDate today = now.toLocalDate();
        int sent = 0;
        for (Asset asset : assets.findBorrowReminderCandidates(today, today.plusDays(ADVANCE_DAYS), limit)) {
            var borrower = users.findById(asset.getBorrowerId());
            if (borrower == null || !"ACTIVE".equals(borrower.getStatus())) continue;
            String no = asset.getNo();
            LocalDate due = asset.getBorrowDue();
            boolean overdue = due.isBefore(today);
            int marked = overdue ? assets.markBorrowOverdue(no, borrower.getId(), due, now)
                    : assets.markBorrowSoon(no, borrower.getId(), due, now);
            if (marked == 0) continue;
            notify.send(borrower.getId(), borrower.getName(), overdue ? "借用设备已逾期" : "借用设备即将到期",
                    asset.getName() + "（" + no + "）约定归还日期为 " + due + "，请办理归还。", "BORROW_DUE", no);
            sent++;
        }
        return sent;
    }
}

