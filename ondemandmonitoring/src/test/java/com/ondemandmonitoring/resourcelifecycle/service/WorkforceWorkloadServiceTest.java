package com.ondemandmonitoring.resourcelifecycle.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.ResourceTimeLock;
import com.ondemandmonitoring.mission.repository.ResourceTimeLockRepository;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.StaffWorkloadResponse;
import com.ondemandmonitoring.resourcelifecycle.entity.ResourcePolicyEntity;
import com.ondemandmonitoring.resourcelifecycle.repository.ResourcePolicyRepository;
import com.ondemandmonitoring.resourcelifecycle.repository.ResourceUsageRecordRepository;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.repository.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * BA-02: the assignment check must count missions already scheduled for the same day,
 * while scheduled missions on other days must not block.
 */
class WorkforceWorkloadServiceTest {
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate DAY = LocalDate.now(VN).plusDays(3);

    private ResourceTimeLockRepository locks;
    private WorkforceWorkloadService service;
    private User staff;
    private ResourcePolicyEntity policy;
    private final List<ResourceTimeLock> lockRows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        UserRepository users = mock(UserRepository.class);
        ResourceUsageRecordRepository usage = mock(ResourceUsageRecordRepository.class);
        locks = mock(ResourceTimeLockRepository.class);
        when(usage.findByResourceKindAndResourceIdAndStatusOrderByStartedAtAsc(anyString(), anyString(), eq("VERIFIED")))
                .thenReturn(List.of());
        when(locks.findAll()).thenReturn(lockRows);
        service = new WorkforceWorkloadService(users, usage, locks, mock(ResourcePolicyRepository.class),
                new LifecycleJsonHelper(new ObjectMapper()));

        staff = new User();
        staff.setId("staff-a");
        staff.setFullName("Staff A");

        policy = new ResourcePolicyEntity();
        policy.setFamily("WORKFORCE");
        policy.setActive(true);
        policy.setRulesJson("{\"maxDailyHours\":8,\"maxWeeklyHours\":40,\"minRestHours\":0,\"warningPercent\":80}");
    }

    private Instant at(LocalDate day, int hour) {
        return day.atTime(hour, 0).atZone(VN).toInstant();
    }

    private void scheduled(String missionId, LocalDate day, int fromHour, int toHour) {
        Mission mission = new Mission();
        mission.setId(missionId);
        ResourceTimeLock lock = new ResourceTimeLock();
        lock.setResourceType("STAFF");
        lock.setResourceId("staff-a");
        lock.setMission(mission);
        lock.setStartTime(at(day, fromHour));
        lock.setEndTime(at(day, toHour));
        lockRows.add(lock);
    }

    private StaffWorkloadResponse assign(LocalDate day, int fromHour, int toHour) {
        return service.evaluateStaff(staff, at(day, fromHour), at(day, toHour), "new-mission", policy);
    }

    @Test
    void sameDayScheduledMissionsCountTowardDailyLimit() {
        scheduled("m1", DAY, 8, 12);
        scheduled("m2", DAY, 13, 17);

        StaffWorkloadResponse result = assign(DAY, 18, 21); // 4 + 4 + 3 = 11h > 8h

        assertThat(result.blocked()).isTrue();
        assertThat(result.blockingReasons()).contains("DAILY_WORKLOAD_LIMIT_EXCEEDED");
    }

    @Test
    void scheduledMissionsOnAnotherDayDoNotBlock() {
        scheduled("m1", DAY.minusDays(1), 8, 12);
        scheduled("m2", DAY.minusDays(1), 13, 17);

        StaffWorkloadResponse result = assign(DAY, 18, 21);

        assertThat(result.blocked()).isFalse();
    }

    @Test
    void reEvaluatingTheSameMissionIsNotDoubleCounted() {
        scheduled("new-mission", DAY, 8, 14); // the mission being assigned already holds a lock

        StaffWorkloadResponse result = assign(DAY, 8, 14);

        assertThat(result.blocked()).isFalse();
    }

    @Test
    void releasedLocksAreIgnored() {
        scheduled("m1", DAY, 8, 16);
        lockRows.getFirst().setReleasedAt(Instant.now());

        assertThat(assign(DAY, 17, 20).blocked()).isFalse();
    }

    @Test
    void displayedWorkloadStaysActualOnly() {
        scheduled("m1", DAY, 8, 12);

        StaffWorkloadResponse display = service.evaluateStaff(staff, null, null, null, policy, DAY);

        assertThat(display.dailyActualSeconds()).isZero();
        assertThat(display.dailyEstimatedSeconds()).isZero();
    }
}
