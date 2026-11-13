package com.ondemandmonitoring.mission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.ResourceTimeLock;
import com.ondemandmonitoring.mission.repository.ResourceTimeLockRepository;
import com.ondemandmonitoring.mission.service.impl.ResourceReservationServiceImpl;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.ondemandmonitoring.userschedule.domain.UserSchedule;
import com.ondemandmonitoring.userschedule.enums.UserScheduleStatus;
import com.ondemandmonitoring.userschedule.enums.UserScheduleType;
import com.ondemandmonitoring.userschedule.repository.UserScheduleRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Staff who self-declared a busy session cannot be reserved for a mission overlapping that session. */
class StaffBusyReservationTest {
    static final Instant START = Instant.parse("2026-11-12T02:00:00Z");
    static final Instant END = Instant.parse("2026-11-12T04:00:00Z");

    ResourceTimeLockRepository locks = mock(ResourceTimeLockRepository.class);
    UserScheduleRepository schedules = mock(UserScheduleRepository.class);
    AuthenticatedUserResolver actor = mock(AuthenticatedUserResolver.class);
    ResourceReservationServiceImpl service =
            new ResourceReservationServiceImpl(mock(JdbcTemplate.class), locks, schedules, actor);
    Mission mission;

    @BeforeEach
    void setUp() {
        mission = new Mission();
        mission.setId("m-1");
        mission.setScheduledStartAt(START);
        mission.setScheduledEndAt(END);
        when(actor.getCurrentUserId()).thenReturn("manager");
        when(locks.findAllByResourceIdAndMissionId(anyString(), anyString())).thenReturn(List.of());
        when(schedules.findByStaffIdInAndScheduleTypeAndStatusNotInAndStartTimeLessThanAndEndTimeGreaterThan(
                anyCollection(), any(), anyCollection(), any(), any())).thenReturn(List.of());
    }

    private void markBusy(String staffId) {
        when(schedules.findByStaffIdInAndScheduleTypeAndStatusNotInAndStartTimeLessThanAndEndTimeGreaterThan(
                eq(List.of(staffId)), eq(UserScheduleType.LEAVE), anyCollection(), any(), any()))
                .thenReturn(List.of(UserSchedule.builder().scheduleType(UserScheduleType.LEAVE)
                        .status(UserScheduleStatus.SCHEDULED).build()));
    }

    @Test
    void assigningBusyStaffIsRejectedAndNothingIsLocked() {
        markBusy("op-1");
        assertThatThrownBy(() -> service.reserve(mission, "op-1", true))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.STAFF_BUSY_IN_SLOT));
        verify(locks, never()).saveAndFlush(any());
    }

    @Test
    void assigningFreeStaffSucceeds() {
        service.reserve(mission, "op-1", true);
        verify(locks).saveAndFlush(any(ResourceTimeLock.class));
    }

    @Test
    void busyQueryUsesMissionWindowAndIgnoresCancelledOrCompleted() {
        service.reserve(mission, "op-1", true);
        verify(schedules).findByStaffIdInAndScheduleTypeAndStatusNotInAndStartTimeLessThanAndEndTimeGreaterThan(
                eq(List.of("op-1")), eq(UserScheduleType.LEAVE),
                eq(java.util.Set.of(UserScheduleStatus.CANCELLED, UserScheduleStatus.COMPLETED)), eq(END), eq(START));
    }

    @Test
    void devicesAreNotCheckedAgainstBusySchedules() {
        markBusy("drone-1");
        assertThatCode(() -> service.reserve(mission, "drone-1", false)).doesNotThrowAnyException();
    }

    @Test
    void bulkReservationFailsWhenAnyStaffIsBusy() {
        markBusy("op-2");
        assertThatThrownBy(() -> service.reserveAll(mission, Map.of("op-1", true, "op-2", true, "drone-1", false)))
                .isInstanceOf(ApiException.class);
        verify(locks, never()).saveAndFlush(any());
    }

    @Test
    void reschedulingIntoBusySessionOfAssignedStaffIsRejected() {
        ResourceTimeLock staffLock = ResourceTimeLock.builder().resourceId("op-1").resourceType("STAFF")
                .mission(mission).startTime(START).endTime(END).build();
        when(locks.findByMissionId("m-1")).thenReturn(List.of(staffLock));
        markBusy("op-1");
        Instant newStart = START.plusSeconds(3600 * 6), newEnd = END.plusSeconds(3600 * 6);
        assertThatThrownBy(() -> service.revalidateSchedule(mission, newStart, newEnd))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.STAFF_BUSY_IN_SLOT));
        assertThat(staffLock.getStartTime()).isEqualTo(START);
    }
}
