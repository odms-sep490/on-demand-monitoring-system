package com.ondemandmonitoring.resourcelifecycle.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.AlertActionRequest;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.AlertResponse;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.AuditLogResponse;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.DroneHealthResponse;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.EligibilityResponse;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.MaintenanceCycleResponse;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.MaintenanceInspectionRequest;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.MaintenanceWorkSubmissionRequest;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.OverviewResponse;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.PolicyActionRequest;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.PolicyImpactPreview;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.PolicyRequest;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.PolicyResponse;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.PolicyVersionResponse;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.RecommendationResponse;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.ScheduleForecastItem;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.SpecificationRequest;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.SpecificationResponse;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.StaffWorkloadResponse;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.UsageRequest;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.UsageResponse;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.VerificationRequest;
import com.ondemandmonitoring.resourcelifecycle.repository.ResourceMaintenanceCycleRepository;
import com.ondemandmonitoring.resourcelifecycle.repository.ResourcePolicyRepository;
import com.ondemandmonitoring.resourcelifecycle.service.AllocationRecommendationService;
import com.ondemandmonitoring.resourcelifecycle.service.DeviceSpecificationService;
import com.ondemandmonitoring.resourcelifecycle.service.DroneHealthService;
import com.ondemandmonitoring.resourcelifecycle.service.MaintenanceLifecycleService;
import com.ondemandmonitoring.resourcelifecycle.service.ResourceAlertService;
import com.ondemandmonitoring.resourcelifecycle.service.ResourceAuditService;
import com.ondemandmonitoring.resourcelifecycle.service.ResourceEligibilityService;
import com.ondemandmonitoring.resourcelifecycle.service.ResourcePolicyService;
import com.ondemandmonitoring.resourcelifecycle.service.ResourceUsageService;
import com.ondemandmonitoring.resourcelifecycle.service.WorkforceWorkloadService;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin REST controller for Smart Workforce & Drone Lifecycle Management.
 * Delegates all domain logic to focused lifecycle services.
 */
@RestController
@RequestMapping("/api/resource-lifecycle")
@RequiredArgsConstructor
public class ResourceLifecycleController {

    private final DroneHealthService droneHealthService;
    private final WorkforceWorkloadService workforceWorkloadService;
    private final ResourceUsageService usageService;
    private final ResourcePolicyService policyService;
    private final MaintenanceLifecycleService maintenanceLifecycleService;
    private final ResourceEligibilityService eligibilityService;
    private final DeviceSpecificationService specificationService;
    private final ResourceAlertService alertService;
    private final ResourceAuditService auditService;
    private final AllocationRecommendationService recommendationService;
    private final ResourceMaintenanceCycleRepository cycleRepository;
    private final ResourcePolicyRepository policyRepository;
    private final AuthenticatedUserResolver userResolver;

    @GetMapping("/overview")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<ApiResponse<OverviewResponse>> overview() {
        List<DroneHealthResponse> drones = droneHealthService.listAllDroneHealth();
        List<StaffWorkloadResponse> staff = workforceWorkloadService.listAllStaffWorkloads();

        int healthyDrones = (int) drones.stream().filter(d -> "HEALTHY".equals(d.maintenanceStatus()) && !d.blocked()).count();
        int warningDrones = (int) drones.stream().filter(d -> "MAINTENANCE_DUE_SOON".equals(d.maintenanceStatus()) || "MONITOR".equals(d.maintenanceStatus())).count();
        int blockedDrones = (int) drones.stream().filter(DroneHealthResponse::blocked).count();
        int unknownDrones = (int) drones.stream().filter(d -> "UNKNOWN".equals(d.maintenanceStatus())).count();

        int warningStaff = (int) staff.stream().filter(s -> "WARNING".equals(s.workloadStatus())).count();
        int blockedStaff = (int) staff.stream().filter(StaffWorkloadResponse::blocked).count();

        OverviewResponse summary = new OverviewResponse(
                drones.size(),
                healthyDrones,
                warningDrones,
                blockedDrones,
                unknownDrones,
                staff.size(),
                warningStaff,
                blockedStaff,
                (int) alertService.countOpenAlerts(),
                (int) cycleRepository.countByCheckpointAtIsNull(),
                (int) policyRepository.countByActiveTrue(),
                DroneHealthService.BATTERY_UNSUPPORTED_NOTE);

        return ResponseEntity.ok(ApiResponse.ok("Resource lifecycle overview loaded", summary));
    }

    @GetMapping("/drones")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','STAFF')")
    public ResponseEntity<ApiResponse<List<DroneHealthResponse>>> listDrones() {
        return ResponseEntity.ok(ApiResponse.ok("Drone health list loaded", droneHealthService.listAllDroneHealth()));
    }

    @GetMapping("/drones/{deviceId}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','STAFF')")
    public ResponseEntity<ApiResponse<DroneHealthResponse>> getDrone(@PathVariable String deviceId) {
        return ResponseEntity.ok(ApiResponse.ok("Drone health loaded", droneHealthService.getDroneHealth(deviceId)));
    }

    @GetMapping("/staff")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<ApiResponse<List<StaffWorkloadResponse>>> listStaff() {
        return ResponseEntity.ok(ApiResponse.ok("Staff workloads loaded", workforceWorkloadService.listAllStaffWorkloads()));
    }

    @GetMapping("/staff/me")
    @PreAuthorize("hasAnyRole('STAFF','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<StaffWorkloadResponse>> myWorkload() {
        String staffId = userResolver.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.ok("Personal workload loaded", workforceWorkloadService.getStaffWorkload(staffId)));
    }

    @GetMapping("/usage")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','STAFF')")
    public ResponseEntity<ApiResponse<List<UsageResponse>>> listUsage(
            @RequestParam(required = false) String resourceKind,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Usage records loaded",
                usageService.listUsageRecords(resourceKind, resourceId, status)));
    }

    @PostMapping("/usage")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','STAFF')")
    public ResponseEntity<ApiResponse<UsageResponse>> recordUsage(@Valid @RequestBody UsageRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Usage record submitted", usageService.recordUsage(request)));
    }

    @PostMapping("/usage/{usageId}/verify")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<ApiResponse<UsageResponse>> verifyUsage(
            @PathVariable UUID usageId,
            @Valid @RequestBody VerificationRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Usage record verified", usageService.verifyUsage(usageId, request)));
    }

    @GetMapping("/policies")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<ApiResponse<List<PolicyResponse>>> listPolicies(
            @RequestParam(required = false) String family) {
        return ResponseEntity.ok(ApiResponse.ok("Policies loaded", policyService.listPolicies(family)));
    }

    @PostMapping("/policies")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<PolicyResponse>> createPolicy(@Valid @RequestBody PolicyRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Policy created", policyService.createPolicy(request)));
    }

    @PutMapping("/policies/{policyId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<PolicyResponse>> updatePolicy(
            @PathVariable UUID policyId,
            @Valid @RequestBody PolicyRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Policy updated", policyService.updatePolicy(policyId, request)));
    }

    @GetMapping("/policies/{policyId}/versions")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<ApiResponse<List<PolicyVersionResponse>>> listPolicyVersions(@PathVariable UUID policyId) {
        return ResponseEntity.ok(ApiResponse.ok("Policy versions loaded", policyService.listVersions(policyId)));
    }

    @PostMapping("/policies/{policyId}/preview")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<PolicyImpactPreview>> previewPolicy(@PathVariable UUID policyId) {
        return ResponseEntity.ok(ApiResponse.ok("Policy impact previewed", policyService.previewImpact(policyId)));
    }

    @PostMapping("/policies/{policyId}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<PolicyResponse>> activatePolicy(
            @PathVariable UUID policyId,
            @Valid @RequestBody PolicyActionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Policy activated", policyService.activatePolicy(policyId, request)));
    }

    @PostMapping("/policies/{policyId}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<PolicyResponse>> deactivatePolicy(
            @PathVariable UUID policyId,
            @Valid @RequestBody PolicyActionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Policy deactivated", policyService.deactivatePolicy(policyId, request)));
    }

    @GetMapping("/maintenance-cycles")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','STAFF')")
    public ResponseEntity<ApiResponse<List<MaintenanceCycleResponse>>> listCycles(
            @RequestParam(defaultValue = "false") boolean openOnly) {
        return ResponseEntity.ok(ApiResponse.ok("Maintenance cycles loaded", maintenanceLifecycleService.listCycles(openOnly)));
    }

    @PostMapping("/maintenance-cycles/{cycleId}/work")
    @PreAuthorize("hasAnyRole('STAFF','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<MaintenanceCycleResponse>> submitWork(
            @PathVariable UUID cycleId,
            @Valid @RequestBody MaintenanceWorkSubmissionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Maintenance work submitted",
                maintenanceLifecycleService.submitWork(cycleId, request)));
    }

    @PostMapping("/maintenance-cycles/{cycleId}/inspect")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<ApiResponse<MaintenanceCycleResponse>> inspectCycle(
            @PathVariable UUID cycleId,
            @Valid @RequestBody MaintenanceInspectionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Post-maintenance inspection recorded",
                maintenanceLifecycleService.recordInspection(cycleId, request)));
    }

    @GetMapping("/eligibility/device/{deviceId}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<ApiResponse<EligibilityResponse>> checkDeviceEligibility(
            @PathVariable String deviceId,
            @RequestParam(required = false) Instant start,
            @RequestParam(required = false) Instant end) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Device eligibility evaluated",
                eligibilityService.evaluateDeviceEligibility(deviceId, start, end)));
    }

    @GetMapping("/eligibility/staff/{staffId}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<ApiResponse<EligibilityResponse>> checkStaffEligibility(
            @PathVariable String staffId,
            @RequestParam(required = false) Instant start,
            @RequestParam(required = false) Instant end,
            @RequestParam(required = false) String excludeMissionId) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Staff eligibility evaluated",
                eligibilityService.evaluateStaffEligibility(staffId, start, end, excludeMissionId)));
    }

    @PostMapping("/missions/{missionId}/recommend")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<ApiResponse<RecommendationResponse>> recommendForMission(@PathVariable String missionId) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Allocation recommendation computed",
                recommendationService.recommendForMission(missionId)));
    }

    @GetMapping("/specifications")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<ApiResponse<List<SpecificationResponse>>> listSpecifications() {
        return ResponseEntity.ok(ApiResponse.ok("Specifications loaded", specificationService.listAllSpecifications()));
    }

    @PutMapping("/specifications/{modelId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<SpecificationResponse>> upsertSpecification(
            @PathVariable String modelId,
            @Valid @RequestBody SpecificationRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Specification saved",
                specificationService.upsertSpecification(modelId, request)));
    }

    @GetMapping("/alerts")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','STAFF')")
    public ResponseEntity<ApiResponse<List<AlertResponse>>> listAlerts(
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(ApiResponse.ok("Alerts loaded", alertService.listAlerts(status)));
    }

    @PostMapping("/alerts/{alertId}/action")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<ApiResponse<AlertResponse>> actOnAlert(
            @PathVariable UUID alertId,
            @Valid @RequestBody AlertActionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Alert updated", alertService.handleAction(alertId, request)));
    }

    @GetMapping("/forecasts/schedule")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<ApiResponse<List<ScheduleForecastItem>>> scheduleForecasts() {
        return ResponseEntity.ok(ApiResponse.ok(
                "Schedule-based maintenance forecasts loaded",
                droneHealthService.computeScheduleBasedForecasts()));
    }

    @GetMapping("/audits")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<ApiResponse<List<AuditLogResponse>>> listAudits(
            @RequestParam(required = false) String entityId) {
        List<AuditLogResponse> audits = (entityId == null || entityId.isBlank())
                ? auditService.recentAudits()
                : auditService.auditsForEntity(entityId.trim());
        return ResponseEntity.ok(ApiResponse.ok("Audit logs loaded", audits));
    }
}
