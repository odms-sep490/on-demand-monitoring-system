package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.mission.service.impl.ReservationWindowPolicy;
import com.ondemandmonitoring.userschedule.domain.UserSchedule;
import com.ondemandmonitoring.userschedule.enums.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.ResourceTimeLock;
import com.ondemandmonitoring.mission.repository.ResourceTimeLockRepository;
import com.ondemandmonitoring.userschedule.repository.UserScheduleRepository;
import com.ondemandmonitoring.mission.service.impl.ResourceReservationServiceImpl;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;

class ReservationWindowPolicyTest {
    Instant at(int hour) { return Instant.parse("2026-10-10T00:00:00Z").plusSeconds(hour*3600L); }
    UserSchedule schedule(UserScheduleType type,int start,int end) {
        return UserSchedule.builder().scheduleType(type).status(UserScheduleStatus.SCHEDULED).startTime(at(start)).endTime(at(end)).build();
    }
    @Test void adjacentIntervalsAreNotOverlappingButOneSecondIs() {
        assertFalse(ReservationWindowPolicy.overlaps(at(9),at(10),at(10),at(11)));
        assertTrue(ReservationWindowPolicy.overlaps(at(9),at(10),at(10).minusSeconds(1),at(11)));
    }
    @Test void oneCoveringShiftIsEnoughAndUnrelatedFutureShiftsDoNotBlock() {
        var rows=List.of(schedule(UserScheduleType.SHIFT,8,12),schedule(UserScheduleType.SHIFT,32,36));
        assertFalse(ReservationWindowPolicy.staffHasConflict(rows,"m",at(9),at(10)));
        assertTrue(ReservationWindowPolicy.staffHasConflict(rows,"m",at(12),at(13)));
    }
    @Test void ownMissionCalendarIsExcludedButSiblingAndLeaveAreExclusive() {
        var owned=schedule(UserScheduleType.MISSION,9,10); owned.setReferenceId("m");
        assertFalse(ReservationWindowPolicy.staffHasConflict(List.of(owned),"m",at(9),at(10)));
        assertTrue(ReservationWindowPolicy.staffHasConflict(List.of(owned),"sibling",at(9),at(10)));
        var leave=schedule(UserScheduleType.LEAVE,9,10);
        assertFalse(ReservationWindowPolicy.staffHasConflict(List.of(leave),"m",at(10),at(11)));
        assertTrue(ReservationWindowPolicy.staffHasConflict(List.of(leave),"m",at(9),at(10)));
        leave.setStatus(UserScheduleStatus.CANCELLED);
        assertFalse(ReservationWindowPolicy.staffHasConflict(List.of(leave),"m",at(9),at(10)));
    }
    @Test void reservationWritesDoNotAcquireMutexRows() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var locks = mock(ResourceTimeLockRepository.class);
        var schedules = mock(UserScheduleRepository.class);
        var actor = mock(AuthenticatedUserResolver.class);
        var service = new ResourceReservationServiceImpl(jdbc, locks, schedules, actor);
        var mission = new Mission(); mission.setId("m"); mission.setScheduledStartAt(at(9)); mission.setScheduledEndAt(at(10));
        service.reserve(mission, "device", false);
        verify(locks).saveAndFlush(any(ResourceTimeLock.class));
        var reservation = ResourceTimeLock.builder().resourceId("device").resourceType("DEVICE").mission(mission)
                .startTime(at(9)).endTime(at(10)).build();
        when(locks.findByMissionId("m")).thenReturn(List.of(reservation));
        service.revalidateSchedule(mission, at(10), at(11));
        assertEquals(at(10), reservation.getStartTime());
        service.release("m", "Cancelled");
        assertNotNull(reservation.getReleasedAt());
        verifyNoInteractions(jdbc);
    }

    @Test void overlapStillRejectsReservationWithoutAcquiringMutexRows() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var locks = mock(ResourceTimeLockRepository.class);
        var service = new ResourceReservationServiceImpl(jdbc, locks, mock(UserScheduleRepository.class), mock(AuthenticatedUserResolver.class));
        var mission = new Mission(); mission.setId("m"); mission.setScheduledStartAt(at(9)); mission.setScheduledEndAt(at(10));
        when(locks.existsActiveOverlap("device", "m", at(9), at(10))).thenReturn(true);
        assertEquals(ErrorCode.RESOURCE_TIME_CONFLICT,
                assertThrows(ApiException.class, () -> service.reserve(mission, "device", false)).getErrorCode());
        verify(locks, never()).saveAndFlush(any());
        verifyNoInteractions(jdbc);
    }

}
