package com.tzme.helpdesk.support;

import java.util.List;

public final class TicketStatus {
    public static final String NEW = "NEW";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String PENDING_USER = "PENDING_USER";
    public static final String PENDING_VENDOR = "PENDING_VENDOR";
    public static final String PENDING_CONFIRM = "PENDING_CONFIRM";
    public static final String CLOSED = "CLOSED";
    public static final String REJECTED = "REJECTED";
    public static final String CANCELLED = "CANCELLED";

    public static final List<String> OPEN = List.of(NEW, ACCEPTED, IN_PROGRESS, PENDING_USER, PENDING_VENDOR);
    public static final List<String> PROCESSING = List.of(ACCEPTED, IN_PROGRESS, PENDING_USER, PENDING_VENDOR);
    public static final List<String> ENDED = List.of(CLOSED, REJECTED, CANCELLED);

    private TicketStatus() {
    }

    public static boolean isEnded(String s) {
        return ENDED.contains(s);
    }
}
