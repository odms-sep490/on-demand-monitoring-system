package com.ondemandmonitoring.device.maintenance.policy;

import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.domain.MaintenanceTicket;
import com.ondemandmonitoring.device.maintenance.dto.MaintenanceWorkflowDtos.ActorSummary;
import com.ondemandmonitoring.device.maintenance.dto.MaintenanceWorkflowDtos.QualifiedTechnicalOption;
import com.ondemandmonitoring.mission.domain.MissionDeviceAssignment;
import com.ondemandmonitoring.mission.enums.MissionStaffRole;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.repository.MissionDeviceAssignmentRepository;
import com.ondemandmonitoring.mission.repository.MissionStaffAssignmentRepository;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.stafflicense.domain.StaffLicense;
import com.ondemandmonitoring.stafflicense.enumeration.LicenseStatus;
import com.ondemandmonitoring.stafflicense.enumeration.LicenseType;
import com.ondemandmonitoring.stafflicense.enumeration.VerificationStatus;
import com.ondemandmonitoring.stafflicense.repository.StaffLicenseRepository;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.repository.UserRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves real domain qualifications for maintenance actors without conflating
 * Technical capability with every generic STAFF account.
 *
 * Technical qualification in this system is determined by:
 * 1) Active STAFF user holding a non-expired, non-rejected StaffLicense of LicenseType.TECHNICAL; OR
 * 2) Active STAFF user currently assigned as MissionStaffRole.MAINTAINER on a mission.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TechnicalCapabilityResolver {

    private static final List<MissionStatus> CUSTODY_MISSION_STATUSES = List.of(
            MissionStatus.PENDING_CONFIRMATION,
            MissionStatus.READY,
            MissionStatus.IN_PROGRESS,
            MissionStatus.AWAITING_REVIEW,
            MissionStatus.PENDING_REVIEW,
            MissionStatus.COMPLETED);

    private final StaffLicenseRepository staffLicenseRepository;
    private final MissionDeviceAssignmentRepository deviceAssignmentRepository;
    private final MissionStaffAssignmentRepository staffAssignmentRepository;
    private final UserRepository userRepository;

    public boolean isQualifiedTechnical(User user) {
        if (user == null || !Boolean.TRUE.equals(user.getIsActive()) || user.getRole() == null) {
            return false;
        }
        if (user.getRole().getCode() != RoleCode.STAFF) {
            return false;
        }
        return hasActiveTechnicalLicense(user.getId()) || hasActiveMaintainerAssignment(user.getId());
    }

    public boolean hasActiveTechnicalLicense(String userId) {
        LocalDate today = LocalDate.now();
        List<StaffLicense> licenses = staffLicenseRepository.findAllByUser_IdOrderByLicenseTypeAscIssuedDateDesc(userId);
        return licenses.stream().anyMatch(license ->
                license.getLicenseType() == LicenseType.TECHNICAL
                        && license.getLicenseStatus() == LicenseStatus.ACTIVE
                        && license.getVerificationStatus() != VerificationStatus.REJECTED
                        && (license.getExpiryDate() == null || !license.getExpiryDate().isBefore(today)));
    }

    public boolean hasActiveMaintainerAssignment(String userId) {
        return staffAssignmentRepository.findAll().stream()
                .anyMatch(assignment -> Boolean.TRUE.equals(assignment.getIsCurrent())
                        && assignment.getReleasedAt() == null
                        && (assignment.getAssignedRole() == MissionStaffRole.TECHNICAL
                                || assignment.getAssignedRole() == MissionStaffRole.MAINTAINER)
                        && assignment.getStaff() != null
                        && userId.equals(assignment.getStaff().getId()));
    }

    public boolean isEligibleReceivingManager(User user) {
        if (user == null || !Boolean.TRUE.equals(user.getIsActive()) || user.getRole() == null) {
            return false;
        }
        RoleCode role = user.getRole().getCode();
        return role == RoleCode.MANAGER || role == RoleCode.ADMIN;
    }

    /**
     * Checks whether the actor currently holds custody of the drone or has management authority
     * to hand it over directly to Technical (without needing Manager pre-approval).
     */
    public boolean canActAsCustodian(User actor, Device device, MaintenanceTicket ticket) {
        if (actor == null || !Boolean.TRUE.equals(actor.getIsActive()) || actor.getRole() == null) {
            return false;
        }
        RoleCode role = actor.getRole().getCode();
        if (role == RoleCode.MANAGER || role == RoleCode.ADMIN) {
            return true;
        }
        if (role != RoleCode.STAFF) {
            return false;
        }
        if (ticket != null) {
            if (ticket.getHandedOverBy() != null && actor.getId().equals(ticket.getHandedOverBy().getId())) {
                return true;
            }
            if (actor.getId().equals(ticket.getReportedBy())) {
                return true;
            }
        }
        if (device == null) {
            return false;
        }
        List<MissionDeviceAssignment> assignments = deviceAssignmentRepository
                .findCurrentByDeviceIdAndMissionStatusIn(device.getId(), CUSTODY_MISSION_STATUSES);
        for (MissionDeviceAssignment assignment : assignments) {
            if (assignment.getMission() == null) {
                continue;
            }
            boolean assignedToMission = staffAssignmentRepository
                    .existsByMissionIdAndStaffIdAndIsCurrentTrueAndReleasedAtIsNull(
                            assignment.getMission().getId(),
                            actor.getId());
            if (assignedToMission) {
                return true;
            }
        }
        return false;
    }

    public List<QualifiedTechnicalOption> listQualifiedTechnicals() {
        List<User> staffUsers = userRepository.findAllByRole_CodeAndIsActiveTrueOrderByFullNameAsc(RoleCode.STAFF);
        List<QualifiedTechnicalOption> options = new ArrayList<>();
        for (User user : staffUsers) {
            if (hasActiveTechnicalLicense(user.getId())) {
                options.add(new QualifiedTechnicalOption(
                        user.getId(),
                        user.getFullName(),
                        user.getEmail(),
                        "TECHNICAL_LICENSE"));
            } else if (hasActiveMaintainerAssignment(user.getId())) {
                options.add(new QualifiedTechnicalOption(
                        user.getId(),
                        user.getFullName(),
                        user.getEmail(),
                        "MISSION_MAINTAINER"));
            }
        }
        return options;
    }

    public List<ActorSummary> listReceivingManagers() {
        List<ActorSummary> managers = new ArrayList<>();
        for (User user : userRepository.findAllByRole_CodeAndIsActiveTrueOrderByFullNameAsc(RoleCode.MANAGER)) {
            managers.add(toActorSummary(user));
        }
        for (User user : userRepository.findAllByRole_CodeAndIsActiveTrueOrderByFullNameAsc(RoleCode.ADMIN)) {
            managers.add(toActorSummary(user));
        }
        return managers;
    }

    public static ActorSummary toActorSummary(User user) {
        if (user == null) {
            return null;
        }
        String role = user.getRole() != null && user.getRole().getCode() != null
                ? user.getRole().getCode().name()
                : null;
        return new ActorSummary(user.getId(), user.getFullName(), user.getEmail(), role);
    }
}
