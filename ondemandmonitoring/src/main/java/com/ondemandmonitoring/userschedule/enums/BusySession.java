package com.ondemandmonitoring.userschedule.enums;

import java.time.LocalTime;

/** Working sessions an operator can mark as busy. Times are in the business timezone (Asia/Ho_Chi_Minh). */
public enum BusySession {
    MORNING(LocalTime.of(6, 0), LocalTime.of(12, 0)),
    AFTERNOON(LocalTime.of(12, 0), LocalTime.of(18, 0)),
    EVENING(LocalTime.of(18, 0), LocalTime.of(22, 0));

    private final LocalTime start;
    private final LocalTime end;

    BusySession(LocalTime start, LocalTime end) {
        this.start = start;
        this.end = end;
    }

    public LocalTime start() { return start; }

    public LocalTime end() { return end; }

    public static BusySession fromStart(LocalTime time) {
        for (BusySession session : values()) {
            if (session.start.equals(time)) return session;
        }
        return null;
    }
}
