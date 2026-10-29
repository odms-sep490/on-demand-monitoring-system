package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.device.enums.DeviceStatus;
import com.ondemandmonitoring.mission.enums.FlightPermitStatus;
import com.ondemandmonitoring.mission.enums.MissionResourcePrepareStatus;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.repository.MissionDeviceAssignmentRepository;
import com.ondemandmonitoring.mission.repository.MissionRepository;
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
            MissionStatus.IN_FLIGHT, MissionStatus.IN_PROGRESS, MissionStatus.RETURNING,
            MissionStatus.POSTFLIGHT_CHECKING, MissionStatus.PENDING_REVIEW,
            MissionStatus.COMPLETED, MissionStatus.FAILED, MissionStatus.CANCELLED);

    private final MissionRepository missions;
    private final MissionDeviceAssignmentRepository deviceAssignments;
    private final MissionStaffAssignmentRepository staffAssignments;
    private final ResourceTimeLockRepository locks;
    private final UserScheduleRepository schedules;

    @Scheduled(fixedDelayString = "${app.flight-permit.resource-release-interval-ms:60000}")
    @Transactional
    public void releaseExpiredPermitHolds() {
        Instant now = Instant.now();
        for (var mission : missions
                .findByFlightPermitStatusAndFlightPermitResourceHoldUntilLessThanEqualAndFlightPermitResourcesReleasedAtIsNull(
                        FlightPermitStatus.REJECTED, now)) {
            if (NON_RELEASABLE.contains(mission.getStatus())) {
                continue;
            }
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

            mission.setResourcePrepareStatus(MissionResourcePrepareStatus.PLANNING);
            mission.setFlightPermitResourcesReleasedAt(now);
            log.info("Released mission {} resources after rejected permit hold expired", mission.getId());
        }
    }
}
