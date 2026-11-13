package com.ondemandmonitoring.userschedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.mission.repository.ResourceTimeLockRepository;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.repository.UserRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.ondemandmonitoring.userschedule.domain.UserSchedule;
import com.ondemandmonitoring.userschedule.dto.request.BusyScheduleRequest;
import com.ondemandmonitoring.userschedule.enums.BusySession;
import com.ondemandmonitoring.userschedule.enums.UserScheduleStatus;
import com.ondemandmonitoring.userschedule.enums.UserScheduleType;
import com.ondemandmonitoring.userschedule.repository.UserScheduleRepository;
import com.ondemandmonitoring.userschedule.service.impl.BusyScheduleServiceImpl;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BusyScheduleServiceImplTest {
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    // Wednesday 2026-11-11 08:00 VN; week = Mon 11-09 .. Sun 11-15
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-11T01:00:00Z"), VN);

    UserScheduleRepository schedules = mock(UserScheduleRepository.class);
    UserRepository users = mock(UserRepository.class);
    AuthenticatedUserResolver resolver = mock(AuthenticatedUserResolver.class);
    ResourceTimeLockRepository locks = mock(ResourceTimeLockRepository.class);
    BusyScheduleServiceImpl service;
    User operator;
    List<UserSchedule> store = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        service = new BusyScheduleServiceImpl(schedules, users, resolver, locks);
        Method m = BusyScheduleServiceImpl.class.getDeclaredMethod("setClock", Clock.class);
        m.setAccessible(true);
        m.invoke(service, CLOCK);
        operator = user("op-1", RoleCode.STAFF);
        when(resolver.getCurrentUser()).thenReturn(operator);
        when(users.findLockedById("op-1")).thenReturn(Optional.of(operator));
        when(schedules.save(any())).thenAnswer(inv -> {
            UserSchedule s = inv.getArgument(0);
            if (s.getId() == null) { s.setId("s-" + store.size()); store.add(s); }
            return s;
        });
        when(schedules.findByStaffIdAndScheduleTypeAndStatusNotInAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                eq("op-1"), eq(UserScheduleType.LEAVE), any(), any(), any())).thenAnswer(inv -> {
            Instant from = inv.getArgument(3), to = inv.getArgument(4);
            return store.stream().filter(s -> s.getStatus() == UserScheduleStatus.SCHEDULED
                    && !s.getStartTime().isBefore(from) && s.getStartTime().isBefore(to)).toList();
        });
        when(schedules.findByStaffIdAndStatusNotInAndStartTimeLessThanAndEndTimeGreaterThan(anyString(), any(), any(), any()))
                .thenReturn(List.of());
        when(schedules.findById(anyString())).thenAnswer(inv ->
                store.stream().filter(s -> s.getId().equals(inv.getArgument(0))).findFirst());
    }

    @Test
    void createsBusySessionAsLeaveRowWithSessionTimes() {
        var res = service.create(req("2026-11-12", BusySession.AFTERNOON));
        assertThat(res.session()).isEqualTo(BusySession.AFTERNOON);
        assertThat(res.startTime()).isEqualTo(Instant.parse("2026-11-12T05:00:00Z"));
        assertThat(res.endTime()).isEqualTo(Instant.parse("2026-11-12T11:00:00Z"));
        assertThat(store.get(0).getScheduleType()).isEqualTo(UserScheduleType.LEAVE);
    }

    @Test
    void rejectsFifthSessionInSameWeekButAllowsNextWeek() {
        service.create(req("2026-11-12", BusySession.MORNING));
        service.create(req("2026-11-13", BusySession.MORNING));
        service.create(req("2026-11-14", BusySession.EVENING));
        service.create(req("2026-11-15", BusySession.EVENING)); // Sunday, same week
        assertError(() -> service.create(req("2026-11-15", BusySession.MORNING)),
                ErrorCode.BUSY_SCHEDULE_WEEKLY_LIMIT_EXCEEDED);
        service.create(req("2026-11-16", BusySession.MORNING)); // next Monday
        assertThat(service.getMyWeek(LocalDate.parse("2026-11-11")).remainingSessions()).isZero();
    }

    @Test
    void cancellingFreesQuotaAndUpdateWithinFullWeekIsAllowed() {
        var a = service.create(req("2026-11-12", BusySession.MORNING));
        service.create(req("2026-11-13", BusySession.MORNING));
        service.create(req("2026-11-14", BusySession.MORNING));
        service.create(req("2026-11-15", BusySession.MORNING));
        var moved = service.update(a.id(), req("2026-11-12", BusySession.EVENING));
        assertThat(moved.session()).isEqualTo(BusySession.EVENING);
        service.delete(a.id());
        service.create(req("2026-11-15", BusySession.EVENING));
    }

    @Test
    void rejectsDuplicatePastAndNonOperator() {
        service.create(req("2026-11-12", BusySession.MORNING));
        assertError(() -> service.create(req("2026-11-12", BusySession.MORNING)), ErrorCode.BUSY_SCHEDULE_DUPLICATE);
        assertError(() -> service.create(req("2026-11-11", BusySession.MORNING)), ErrorCode.BUSY_SCHEDULE_PAST_DATE);
        when(resolver.getCurrentUser()).thenReturn(user("mgr", RoleCode.MANAGER));
        assertError(() -> service.create(req("2026-11-13", BusySession.MORNING)), ErrorCode.ACCESS_DENIED);
    }

    @Test
    void rejectsSessionOverlappingAssignedMission() {
        UserSchedule mission = UserSchedule.builder().scheduleType(UserScheduleType.MISSION)
                .status(UserScheduleStatus.SCHEDULED).build();
        when(schedules.findByStaffIdAndStatusNotInAndStartTimeLessThanAndEndTimeGreaterThan(anyString(), any(), any(), any()))
                .thenReturn(List.of(mission));
        assertError(() -> service.create(req("2026-11-12", BusySession.MORNING)), ErrorCode.BUSY_SCHEDULE_MISSION_CONFLICT);
    }

    @Test
    void rejectsSessionOverlappingPlanningStaffLock() {
        when(locks.existsActiveOverlap(eq("op-1"), eq(""), any(), any())).thenReturn(true);
        assertError(() -> service.create(req("2026-11-12", BusySession.MORNING)), ErrorCode.BUSY_SCHEDULE_MISSION_CONFLICT);
    }

    @Test
    void otherOperatorCannotModify() {
        var a = service.create(req("2026-11-12", BusySession.MORNING));
        User other = user("op-2", RoleCode.STAFF);
        when(resolver.getCurrentUser()).thenReturn(other);
        when(users.findLockedById("op-2")).thenReturn(Optional.of(other));
        assertError(() -> service.delete(a.id()), ErrorCode.BUSY_SCHEDULE_NOT_FOUND);
    }

    private static BusyScheduleRequest req(String date, BusySession session) {
        return new BusyScheduleRequest(LocalDate.parse(date), session, null);
    }

    private static User user(String id, RoleCode code) {
        User u = User.builder().fullName(id).role(Role.builder().code(code).build()).isActive(true).build();
        u.setId(id);
        return u;
    }

    private static void assertError(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(code));
    }
}
