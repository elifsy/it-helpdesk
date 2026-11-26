package com.tzme.helpdesk.domain;

import lombok.Data;

@Data
public class PendingReportCounts {
    private long repair;
    private long receive;
    private long borrow;
    private long replace;
    private long returnCount;
    private long overdueReturn;

    public long total() {
        return repair + receive + borrow + replace + returnCount + overdueReturn;
    }
}
