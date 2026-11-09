package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.userschedule.domain.UserSchedule;
import com.ondemandmonitoring.userschedule.enums.*;
import java.time.Instant;
import java.util.List;

/** Shared advisory/read and transactional/write calendar rules; all intervals are half-open. */
public final class ReservationWindowPolicy {
    private ReservationWindowPolicy() {}
    public static boolean overlaps(Instant start, Instant end, Instant requestedStart, Instant requestedEnd) {
        return start != null && end != null && start.isBefore(requestedEnd) && end.isAfter(requestedStart);
    }
    public static boolean staffHasConflict(List<UserSchedule> rows, String missionId, Instant start, Instant end) {
        var active = rows.stream().filter(s -> s.getStatus() != UserScheduleStatus.CANCELLED
                && s.getStatus() != UserScheduleStatus.COMPLETED).toList();
        var shifts = active.stream().filter(s -> s.getScheduleType() == UserScheduleType.SHIFT || s.getScheduleType() == UserScheduleType.ON_CALL).toList();
        if (!shifts.isEmpty() && shifts.stream().noneMatch(s -> !s.getStartTime().isAfter(start) && !s.getEndTime().isBefore(end))) return true;
        return active.stream().filter(s -> s.getScheduleType() != UserScheduleType.SHIFT && s.getScheduleType() != UserScheduleType.ON_CALL)
                .filter(s -> s.getScheduleType() != UserScheduleType.MISSION || !missionId.equals(s.getReferenceId()))
                .anyMatch(s -> overlaps(s.getStartTime(),s.getEndTime(),start,end));
    }
}
