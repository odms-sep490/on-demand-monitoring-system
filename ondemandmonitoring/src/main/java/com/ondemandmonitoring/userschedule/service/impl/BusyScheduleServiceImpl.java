package com.ondemandmonitoring.userschedule.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.mission.repository.ResourceTimeLockRepository;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.repository.UserRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.ondemandmonitoring.userschedule.domain.UserSchedule;
import com.ondemandmonitoring.userschedule.dto.request.BusyScheduleRequest;
import com.ondemandmonitoring.userschedule.dto.response.BusyScheduleResponse;
import com.ondemandmonitoring.userschedule.dto.response.WeeklyBusyScheduleResponse;
import com.ondemandmonitoring.userschedule.enums.BusySession;
import com.ondemandmonitoring.userschedule.enums.UserScheduleStatus;
import com.ondemandmonitoring.userschedule.enums.UserScheduleType;
import com.ondemandmonitoring.userschedule.repository.UserScheduleRepository;
import com.ondemandmonitoring.userschedule.service.IBusyScheduleService;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operator self-declared busy sessions. Stored as {@link UserScheduleType#LEAVE} rows in {@code user_schedules}
 * so existing staff-calendar conflict rules treat them as blocking time.
 */
@Service
@RequiredArgsConstructor
public class BusyScheduleServiceImpl implements IBusyScheduleService {

    public static final int MAX_SESSIONS_PER_WEEK = 4;
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final UserScheduleType BUSY_TYPE = UserScheduleType.LEAVE;
    static final Set<UserScheduleStatus> INACTIVE = Set.of(UserScheduleStatus.CANCELLED, UserScheduleStatus.COMPLETED);

    private final UserScheduleRepository schedules;
    private final UserRepository users;
    private final AuthenticatedUserResolver currentUser;
    private final ResourceTimeLockRepository resourceLocks;
    private Clock clock = Clock.system(BUSINESS_ZONE);

    @Autowired(required = false)
    void setClock(Clock clock) {
        if (clock != null) this.clock = clock.withZone(BUSINESS_ZONE);
    }

    @Override
    @Transactional(readOnly = true)
    public WeeklyBusyScheduleResponse getMyWeek(LocalDate anyDayInWeek) {
        return week(requireOperator(currentUser.getCurrentUser()).getId(), anyDayInWeek);
    }

    @Override
    @Transactional(readOnly = true)
    public WeeklyBusyScheduleResponse getStaffWeek(String staffId, LocalDate anyDayInWeek) {
        users.findById(staffId).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
        return week(staffId, anyDayInWeek);
    }

    @Override
    @Transactional(readOnly = true)
    public BusyScheduleResponse get(String id) {
        User user = currentUser.getCurrentUser();
        UserSchedule schedule = findActive(id);
        RoleCode role = user.getRole().getCode();
        if (role != RoleCode.MANAGER && role != RoleCode.ADMIN && !isOwner(schedule, user)) {
            throw new ApiException(ErrorCode.BUSY_SCHEDULE_NOT_FOUND);
        }
        return toResponse(schedule);
    }

    @Override
    @Transactional
    public BusyScheduleResponse create(BusyScheduleRequest request) {
        User staff = lockOperator();
        validateSlot(staff.getId(), request, null);
        UserSchedule schedule = UserSchedule.builder()
                .staff(staff)
                .scheduleType(BUSY_TYPE)
                .status(UserScheduleStatus.SCHEDULED)
                .build();
        apply(schedule, request);
        return toResponse(schedules.save(schedule));
    }

    @Override
    @Transactional
    public BusyScheduleResponse update(String id, BusyScheduleRequest request) {
        User staff = lockOperator();
        UserSchedule schedule = findOwned(id, staff);
        ensureNotStarted(schedule);
        validateSlot(staff.getId(), request, schedule.getId());
        apply(schedule, request);
        return toResponse(schedules.save(schedule));
    }

    @Override
    @Transactional
    public void delete(String id) {
        User staff = lockOperator();
        UserSchedule schedule = findOwned(id, staff);
        ensureNotStarted(schedule);
        schedule.setStatus(UserScheduleStatus.CANCELLED);
        schedules.save(schedule);
    }

    // ---- rules ----

    private void validateSlot(String staffId, BusyScheduleRequest request, String excludeId) {
        Instant start = startOf(request.date(), request.session());
        Instant end = endOf(request.date(), request.session());
        if (!start.isAfter(clock.instant())) {
            throw new ApiException(ErrorCode.BUSY_SCHEDULE_PAST_DATE);
        }
        List<UserSchedule> weekRows = activeInWeek(staffId, request.date()).stream()
                .filter(s -> !s.getId().equals(excludeId))
                .toList();
        if (weekRows.stream().anyMatch(s -> s.getStartTime().equals(start))) {
            throw new ApiException(ErrorCode.BUSY_SCHEDULE_DUPLICATE);
        }
        if (weekRows.size() >= MAX_SESSIONS_PER_WEEK) {
            throw new ApiException(ErrorCode.BUSY_SCHEDULE_WEEKLY_LIMIT_EXCEEDED);
        }
        boolean missionConflict = schedules
                .findByStaffIdAndStatusNotInAndStartTimeLessThanAndEndTimeGreaterThan(staffId, INACTIVE, end, start)
                .stream().anyMatch(s -> s.getScheduleType() == UserScheduleType.MISSION);
        // Also covers planning-stage assignments that hold a STAFF time lock but no MISSION schedule row yet.
        missionConflict = missionConflict || resourceLocks.existsActiveOverlap(staffId, "", start, end);
        if (missionConflict) {
            throw new ApiException(ErrorCode.BUSY_SCHEDULE_MISSION_CONFLICT);
        }
    }

    private void ensureNotStarted(UserSchedule schedule) {
        if (!schedule.getStartTime().isAfter(clock.instant())) {
            throw new ApiException(ErrorCode.BUSY_SCHEDULE_PAST_DATE);
        }
    }

    private User lockOperator() {
        User user = requireOperator(currentUser.getCurrentUser());
        return users.findLockedById(user.getId()).orElse(user);
    }

    private User requireOperator(User user) {
        if (user.getRole() == null || user.getRole().getCode() != RoleCode.STAFF) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Only drone operators can manage busy schedules");
        }
        return user;
    }

    private UserSchedule findActive(String id) {
        return schedules.findById(id)
                .filter(s -> s.getScheduleType() == BUSY_TYPE && !INACTIVE.contains(s.getStatus()))
                .orElseThrow(() -> new ApiException(ErrorCode.BUSY_SCHEDULE_NOT_FOUND));
    }

    private UserSchedule findOwned(String id, User staff) {
        UserSchedule schedule = findActive(id);
        if (!isOwner(schedule, staff)) throw new ApiException(ErrorCode.BUSY_SCHEDULE_NOT_FOUND);
        return schedule;
    }

    private static boolean isOwner(UserSchedule schedule, User user) {
        return schedule.getStaff() != null && Objects.equals(schedule.getStaff().getId(), user.getId());
    }

    // ---- helpers ----

    private WeeklyBusyScheduleResponse week(String staffId, LocalDate anyDayInWeek) {
        LocalDate day = anyDayInWeek != null ? anyDayInWeek : LocalDate.now(clock);
        LocalDate monday = weekStart(day);
        List<BusyScheduleResponse> items = activeInWeek(staffId, day).stream().map(this::toResponse).toList();
        return WeeklyBusyScheduleResponse.builder()
                .weekStart(monday)
                .weekEnd(monday.plusDays(6))
                .maxSessionsPerWeek(MAX_SESSIONS_PER_WEEK)
                .usedSessions(items.size())
                .remainingSessions(Math.max(0, MAX_SESSIONS_PER_WEEK - items.size()))
                .items(items)
                .build();
    }

    private List<UserSchedule> activeInWeek(String staffId, LocalDate anyDayInWeek) {
        LocalDate monday = weekStart(anyDayInWeek);
        Instant from = monday.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant to = monday.plusWeeks(1).atStartOfDay(BUSINESS_ZONE).toInstant();
        return schedules
                .findByStaffIdAndScheduleTypeAndStatusNotInAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                        staffId, BUSY_TYPE, INACTIVE, from, to);
    }

    static LocalDate weekStart(LocalDate day) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static void apply(UserSchedule schedule, BusyScheduleRequest request) {
        schedule.setStartTime(startOf(request.date(), request.session()));
        schedule.setEndTime(endOf(request.date(), request.session()));
        schedule.setNotes(request.notes() == null || request.notes().isBlank() ? null : request.notes().trim());
    }

    private static Instant startOf(LocalDate date, BusySession session) {
        return date.atTime(session.start()).atZone(BUSINESS_ZONE).toInstant();
    }

    private static Instant endOf(LocalDate date, BusySession session) {
        return date.atTime(session.end()).atZone(BUSINESS_ZONE).toInstant();
    }

    private BusyScheduleResponse toResponse(UserSchedule schedule) {
        ZonedDateTime start = schedule.getStartTime().atZone(BUSINESS_ZONE);
        User staff = schedule.getStaff();
        return BusyScheduleResponse.builder()
                .id(schedule.getId())
                .staffId(staff != null ? staff.getId() : null)
                .staffName(staff != null ? staff.getFullName() : null)
                .date(start.toLocalDate())
                .dayOfWeek(start.getDayOfWeek())
                .session(BusySession.fromStart(start.toLocalTime()))
                .startTime(schedule.getStartTime())
                .endTime(schedule.getEndTime())
                .notes(schedule.getNotes())
                .build();
    }
}
