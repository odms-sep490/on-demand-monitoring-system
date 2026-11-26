package com.ondemandmonitoring.resourcelifecycle.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.maintenance.service.MaintenanceEligibilityGuard;
import com.ondemandmonitoring.device.repository.DeviceRepository;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.DroneHealthResponse;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.EligibilityResponse;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.StaffWorkloadResponse;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shared hard-constraint eligibility boundary used by BOTH mission assignment/reservation
 * writes and deterministic allocation recommendations.
 */
@Service
@RequiredArgsConstructor
public class ResourceEligibilityService {

    private final DeviceRepository deviceRepository;
    private final UserRepository userRepository;
    private final DroneHealthService droneHealthService;
    private final WorkforceWorkloadService workforceWorkloadService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MaintenanceEligibilityGuard maintenanceEligibilityGuard;

    @Transactional(readOnly = true)
    public EligibilityResponse evaluateDeviceEligibility(
            String deviceId, Instant missionStart, Instant missionEnd) {
        Device device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null) {
            return new EligibilityResponse(false, "BLOCKED", List.of("DEVICE_NOT_FOUND"), List.of(), Map.of());
        }

        DroneHealthResponse health = droneHealthService.evaluateDevice(device, null);
        List<String> blocking = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (health.blocked()) {
            blocking.addAll(health.reasons());
        } else if ("MAINTENANCE_DUE_SOON".equals(health.maintenanceStatus())) {
            warnings.add("MAINTENANCE_DUE_SOON");
        }

        if (maintenanceEligibilityGuard != null
                && maintenanceEligibilityGuard.hasBlockingMaintenanceTicket(device.getId(), null)
                && !blocking.contains("OPEN_MAINTENANCE_WORKFLOW")) {
            blocking.add("OPEN_MAINTENANCE_WORKFLOW");
        }

        long projectedSeconds = (missionStart != null && missionEnd != null && missionStart.isBefore(missionEnd))
                ? Duration.between(missionStart, missionEnd).getSeconds()
                : 0L;

        if (projectedSeconds > 0L
                && health.remainingSeconds() != null
                && projectedSeconds > health.remainingSeconds()) {
            blocking.add("PROJECTED_MISSION_EXCEEDS_REMAINING_MAINTENANCE_BUDGET");
        }

        boolean eligible = blocking.isEmpty();
        String status = !eligible ? "BLOCKED" : warnings.isEmpty() ? "ELIGIBLE" : "WARNING";

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("maintenanceStatus", health.maintenanceStatus());
        facts.put("remainingSeconds", health.remainingSeconds());
        facts.put("projectedMissionSeconds", projectedSeconds);
        facts.put("usageSource", health.usageSource());
        facts.put("openTicketId", health.openTicketId());

        return new EligibilityResponse(eligible, status, List.copyOf(blocking), List.copyOf(warnings), facts);
    }

    @Transactional(readOnly = true)
    public EligibilityResponse evaluateStaffEligibility(
            String staffId, Instant missionStart, Instant missionEnd, String excludeMissionId) {
        User staff = userRepository.findById(staffId).orElse(null);
        if (staff == null) {
            return new EligibilityResponse(false, "BLOCKED", List.of("STAFF_NOT_FOUND"), List.of(), Map.of());
        }

        StaffWorkloadResponse workload = workforceWorkloadService
                .evaluateStaff(staff, missionStart, missionEnd, excludeMissionId, null);

        boolean eligible = !workload.blocked();
        String status = !eligible
                ? "BLOCKED"
                : workload.warningReasons().isEmpty() ? "ELIGIBLE" : "WARNING";

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("dailyActualSeconds", workload.dailyActualSeconds());
        facts.put("dailyEstimatedSeconds", workload.dailyEstimatedSeconds());
        facts.put("weeklyActualSeconds", workload.weeklyActualSeconds());
        facts.put("weeklyEstimatedSeconds", workload.weeklyEstimatedSeconds());
        facts.put("restSecondsBeforeNextMission", workload.restSecondsBeforeNextMission());
        facts.put("maxUtilizationPercent", workload.maxUtilizationPercent());

        return new EligibilityResponse(
                eligible, status, workload.blockingReasons(), workload.warningReasons(), facts);
    }

    @Transactional(readOnly = true)
    public void requireResourceEligibleForMission(
            String resourceId,
            boolean isStaff,
            Instant missionStart,
            Instant missionEnd,
            String missionId) {
        EligibilityResponse eligibility = isStaff
                ? evaluateStaffEligibility(resourceId, missionStart, missionEnd, missionId)
                : evaluateDeviceEligibility(resourceId, missionStart, missionEnd);
        if (!eligibility.eligible()) {
            String message = (isStaff ? "Staff " : "Drone ") + resourceId
                    + " is not eligible for assignment: " + String.join(", ", eligibility.blockingReasons());
            throw new ApiException(HttpStatus.CONFLICT, message);
        }
    }
}
