package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.enums.MissionDeviceCheckStatus;
import com.ondemandmonitoring.mission.enums.MissionStaffAssignmentStatus;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.repository.MissionDeviceAssignmentRepository;
import com.ondemandmonitoring.mission.repository.MissionStaffAssignmentRepository;
import com.ondemandmonitoring.mission.service.IMissionChecklistMaterializationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Shared transition from a finalized planning scope into flight-permit preparation. */
@Service
@RequiredArgsConstructor
public class MissionPlanningActivationService {
    private final IMissionChecklistMaterializationService materialization;
    private final MissionStaffAssignmentRepository staffAssignments;
    private final MissionDeviceAssignmentRepository deviceAssignments;

    public void activate(Mission mission) {
        materialization.materialize(mission);
        mission.setStatus(MissionStatus.PENDING_CONFIRMATION);
        staffAssignments.findByMissionId(mission.getId()).stream()
                .filter(assignment -> assignment.getReleasedAt() == null)
                .forEach(assignment -> {
                    if (assignment.getResponseStatus() == MissionStaffAssignmentStatus.PLANNING)
                        assignment.setResponseStatus(MissionStaffAssignmentStatus.PENDING);
                    assignment.setIsCurrent(true);
                });
        deviceAssignments.findByMissionId(mission.getId()).stream()
                .filter(assignment -> assignment.getReleasedAt() == null)
                .forEach(assignment -> {
                    if (assignment.getCheckupStatus() == MissionDeviceCheckStatus.PLANNING)
                        assignment.setCheckupStatus(MissionDeviceCheckStatus.PENDING);
                    assignment.setIsCurrent(true);
                });
    }
}
