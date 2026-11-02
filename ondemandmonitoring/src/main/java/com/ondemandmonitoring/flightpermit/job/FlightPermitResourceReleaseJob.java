package com.ondemandmonitoring.flightpermit.job;

import com.ondemandmonitoring.device.enums.DeviceStatus;
import com.ondemandmonitoring.mission.enums.FlightPermitStatus;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.repository.MissionDeviceAssignmentRepository;
import com.ondemandmonitoring.flightpermit.repository.FlightPermitRepository;
import com.ondemandmonitoring.mission.repository.MissionStaffAssignmentRepository;
import com.ondemandmonitoring.mission.repository.ResourceTimeLockRepository;
import com.ondemandmonitoring.userschedule.enums.UserScheduleStatus;
import com.ondemandmonitoring.userschedule.repository.UserScheduleRepository;
import java.time.Instant;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class FlightPermitResourceReleaseJob {
    private static final Set<MissionStatus> NON_RELEASABLE = Set.of(
            MissionStatus.READY, MissionStatus.IN_PROGRESS, MissionStatus.AWAITING_REVIEW,
            MissionStatus.PENDING_REVIEW,
            MissionStatus.COMPLETED, MissionStatus.FAILED, MissionStatus.CANCELLED);

    private final FlightPermitRepository flightPermits;
    private final MissionDeviceAssignmentRepository deviceAssignments;
    private final MissionStaffAssignmentRepository staffAssignments;
    private final ResourceTimeLockRepository locks;
    private final UserScheduleRepository schedules;

    @Scheduled(fixedDelayString = "${app.flight-permit.resource-release-interval-ms:60000}")
    @Transactional
    public void releaseExpiredPermitHolds() {
        Instant now = Instant.now();
        for (var permit : flightPermits
                .findByStatusAndResourceHoldUntilLessThanEqualAndResourcesReleasedAtIsNull(
                        FlightPermitStatus.REJECTED, now)) {
            var mission = permit.getMission();
            if (NON_RELEASABLE.contains(mission.getStatus())) continue;

            deviceAssignments.findAllByMissionIdAndIsCurrentTrueOrderByCreatedAtDesc(mission.getId())
                    .forEach(assignment -> {
                        assignment.setIsCurrent(false);
                        assignment.setReleasedAt(now);
                        assignment.setReleaseReason("FLIGHT_PERMIT_HOLD_EXPIRED");
                        if (assignment.getDevice() != null
                                && assignment.getDevice().getStatus() == DeviceStatus.RESERVED) {
                            assignment.getDevice().setStatus(DeviceStatus.AVAILABLE);
                        }
                    });
            staffAssignments.findAllByMissionIdAndIsCurrentTrue(mission.getId())
                    .forEach(assignment -> {
                        assignment.setIsCurrent(false);
                        assignment.setReleasedAt(now);
                        assignment.setReleaseReason("FLIGHT_PERMIT_HOLD_EXPIRED");
                    });
            locks.findByMissionId(mission.getId()).stream()
                    .filter(lock -> lock.getReleasedAt() == null)
                    .forEach(lock -> {
                        lock.setReleasedAt(now);
                        lock.setReleaseReason("FLIGHT_PERMIT_HOLD_EXPIRED");
                        lock.setReleasedBy("SYSTEM");
                    });
            schedules.findByReferenceId(mission.getId()).stream()
                    .filter(schedule -> schedule.getStatus() != UserScheduleStatus.COMPLETED)
                    .forEach(schedule -> schedule.setStatus(UserScheduleStatus.CANCELLED));

            permit.setResourcesReleasedAt(now);
            log.info("Released mission {} resources after rejected permit hold expired", mission.getId());
        }
    }
}
