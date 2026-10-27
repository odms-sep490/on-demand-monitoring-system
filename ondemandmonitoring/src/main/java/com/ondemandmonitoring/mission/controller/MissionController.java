package com.ondemandmonitoring.mission.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.common.api.PageResponse;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.devicecheck.dto.response.PreDeviceCheckResponse;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.dto.response.MediaAssetResponse;
import com.ondemandmonitoring.media.mapper.MediaAssetMapper;
import com.ondemandmonitoring.mission.dto.request.AssignDeviceRequest;
import com.ondemandmonitoring.mission.dto.request.AssignStaffRequest;
import com.ondemandmonitoring.mission.dto.request.MissionFailRequest;
import com.ondemandmonitoring.mission.dto.request.MissionRejectRequest;
import com.ondemandmonitoring.mission.dto.request.MissionUpdateRequest;
import com.ondemandmonitoring.mission.dto.request.PostFlightStatusRequest;
import com.ondemandmonitoring.mission.dto.request.FlightPermitUpdateRequest;
import com.ondemandmonitoring.mission.dto.response.MissionPlanResponse;
import com.ondemandmonitoring.mission.dto.response.MissionResponse;
import com.ondemandmonitoring.mission.dto.response.MissionTelemetryReadinessResponse;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.service.IMissionMediaUploadService;
import com.ondemandmonitoring.mission.service.IMissionService;
import com.ondemandmonitoring.mission.dto.request.MissionCreateRequest;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * REST entry point for all Flow 3 staff mission lifecycle use cases.
 * Enterprise pattern: Thin controller receiving DTOs directly from Service
 * layer.
 *
 * Base path: /api/missions
 */
@Tag(name = "Mission Operations (Flow 3)", description = "APIs for staff mission lifecycle, GCS pairing, pre-device checks, and flight execution")
@RestController
@RequestMapping("/api/missions")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@PreAuthorize("hasAnyRole('MANAGER','STAFF','ADMIN')")
public class MissionController {

    IMissionService missionService;
    IMissionMediaUploadService missionMediaUploadService;
    MediaAssetMapper mediaAssetMapper;

    // ------------------------------------------------------------------
    // Query
    // ------------------------------------------------------------------

    @PostMapping("/{orderId}")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<MissionResponse>> createMission(
            @PathVariable String orderId,
            @Valid @RequestBody MissionCreateRequest request) {
        var result = missionService.createMission(orderId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Mission created", result));
    }

    @PostMapping("/{missionId}/replacement")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<MissionResponse>> replaceMission(
            @PathVariable String missionId,
            @Valid @RequestBody MissionCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Replacement mission created", missionService.replaceMission(missionId, request)));
    }

    @PostMapping("/{id}/finalize-replacement")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<MissionResponse>> finalizeReplacementMission(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok("Replacement mission finalized",
                missionService.finalizeReplacementMission(id)));
    }

    @GetMapping("/all")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<List<MissionResponse>>> listAll() {
        return ResponseEntity.ok(ApiResponse.ok(missionService.getAllMissions()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<MissionResponse>> updateMission(
            @PathVariable String id,
            @Valid @RequestBody MissionUpdateRequest request) {
        MissionResponse response = missionService.updateMission(id, request);
        return ResponseEntity.ok(ApiResponse.ok("Cập nhật mission thành công", response));
    }

    /**
     * GET /api/missions?staffId={id}
     * List all missions assigned to staff, sorted by scheduledStartAt
     * desc.
     */
    @GetMapping
    @PreAuthorize("@missionAuthorizationService.canViewStaffMissions(#staffId)")
    public ResponseEntity<ApiResponse<List<MissionResponse>>> listByStaff(
            @RequestParam String staffId) {
        List<MissionResponse> missions = missionService.getByStaffId(staffId);
        return ResponseEntity.ok(ApiResponse.ok(missions));
    }

    /**
     * Staff-only search; leaves the Staff-scoped GET /api/missions contract
     * unchanged.
     */
    @GetMapping("/staff")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<MissionResponse>>> searchStaffMissions(
            @RequestParam(required = false) MissionStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        if (page < 0 || size < 1 || size > 100 || (from != null && to != null && to.isBefore(from))) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Invalid mission search filters or page size");
        }
        ZoneId zone = ZoneId.of("Asia/Ho_Chi_Minh");
        return ResponseEntity.ok(ApiResponse.ok(missionService.searchStaffMissions(
                status,
                from == null ? null : from.atStartOfDay(zone).toInstant(),
                to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant(),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")))));
    }

    @PreAuthorize("hasRole('STAFF')")
    @GetMapping("/mine")
    public ResponseEntity<ApiResponse<List<MissionResponse>>> listCurrentStaffMissions() {
        return ResponseEntity.ok(ApiResponse.ok(missionService.getCurrentStaffMissions()));
    }

    /** GET /api/missions/code/{missionCode} – retrieve mission details by code */
    @GetMapping("/code/{missionCode}")
    @PreAuthorize("@missionAuthorizationService.canViewMissionByCode(#missionCode)")
    public ResponseEntity<ApiResponse<MissionResponse>> getByCode(@PathVariable String missionCode) {
        MissionResponse response = missionService.getByCodeResponse(missionCode);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /** GET /api/missions/{id} – retrieve mission details */
    @GetMapping("/{id}")
    @PreAuthorize("@missionAuthorizationService.canViewMission(#id)")
    public ResponseEntity<ApiResponse<MissionResponse>> getById(@PathVariable String id) {
        MissionResponse response = missionService.getByIdResponse(id);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /** GET /api/missions/{id}/plan – retrieve generated operational MissionPlan */
    @GetMapping("/{id}/plan")
    @PreAuthorize("@missionAuthorizationService.canViewMission(#id)")
    public ResponseEntity<ApiResponse<MissionPlanResponse>> getPlan(@PathVariable String id) {
        MissionPlanResponse response = missionService.getMissionPlan(id);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ------------------------------------------------------------------
    @GetMapping("/pending-assignment")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<List<MissionResponse>>> getPendingAssignment() {
        return ResponseEntity.ok(ApiResponse.ok(missionService.getPendingAssignmentMissions()));
    }

    // F2 – Manager Assignment (Flow 2)
    // ------------------------------------------------------------------

    @PostMapping("/{id}/assign-device")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<MissionResponse>> assignDevice(
            @PathVariable String id,
            @Valid @RequestBody AssignDeviceRequest request) {
        MissionResponse response = missionService.assignDevice(id, request);
        return ResponseEntity.ok(ApiResponse.ok("Gán thiết bị cho mission thành công", response));
    }

    @PostMapping("/{id}/remove-device")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<MissionResponse>> removeDevice(
            @PathVariable String id,
            @Valid @RequestBody com.ondemandmonitoring.mission.dto.request.RemoveDeviceRequest request) {
        MissionResponse response = missionService.removeDevice(id, request);
        return ResponseEntity.ok(ApiResponse.ok("Gỡ thiết bị khỏi mission thành công", response));
    }

    @PostMapping("/{id}/assign-staff")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<MissionResponse>> assignStaff(
            @PathVariable String id,
            @Valid @RequestBody AssignStaffRequest request) {
        MissionResponse response = missionService.assignStaff(id, request);
        return ResponseEntity.ok(ApiResponse.ok("Gán staff cho mission thành công", response));
    }

    @PostMapping("/{id}/remove-staff")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<MissionResponse>> removeStaff(
            @PathVariable String id,
            @Valid @RequestBody com.ondemandmonitoring.mission.dto.request.RemoveStaffRequest request) {
        MissionResponse response = missionService.removeStaff(id, request);
        return ResponseEntity.ok(ApiResponse.ok("Gỡ staff khỏi mission thành công", response));
    }

    @PatchMapping("/{id}/flight-permit")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<MissionResponse>> updateFlightPermit(
            @PathVariable String id,
            @Valid @RequestBody FlightPermitUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Cập nhật giấy phép bay thành công",
                missionService.updateFlightPermit(id, request)));
    }



    // ------------------------------------------------------------------
    // F3.1 – Staff acceptance
    // ------------------------------------------------------------------

    /**
     * PATCH /api/missions/{id}/accept
     * Staff confirms they accept the mission.
     * Transitions: WAITING_OPERATOR_ACCEPTANCE → ASTAR_ENERGY_AWARE planning →
     * SCHEDULED
     */
    @PatchMapping("/{id}/accept")
    @PreAuthorize("@missionAuthorizationService.canRespondToMission(#id)")
    public ResponseEntity<ApiResponse<MissionResponse>> accept(
            @PathVariable String id,
            @RequestHeader(value = "X-Staff-Id", required = false) String ignoredstaffId) {
        MissionResponse response = missionService.acceptCurrentStaffMission(id);
        return ResponseEntity.ok(ApiResponse.ok("Mission đã được chấp nhận", response));
    }

    @PreAuthorize("@missionAuthorizationService.canRespondToMission(#id)")
    @PatchMapping("/{id}/accept-current")
    public ResponseEntity<ApiResponse<MissionResponse>> acceptCurrentStaff(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Mission accepted successfully",
                missionService.acceptCurrentStaffMission(id)));
    }

    @PostMapping("/{id}/start-operation")
    @PreAuthorize("@missionAuthorizationService.canStartMission(#id)")
    public ResponseEntity<ApiResponse<MissionResponse>> startOperation(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Mission workflow started; Operator may connect GCS",
                missionService.startMissionWorkflow(id)));
    }

    @PatchMapping("/{id}/accept-staff")
    @PreAuthorize("@missionAuthorizationService.canRespondToMission(#id,#staffId)")
    public ResponseEntity<ApiResponse<MissionResponse>> acceptByStaffId(
            @PathVariable String id,
            @RequestParam String staffId) {
        MissionResponse response = missionService.acceptMission(id, staffId);
        return ResponseEntity.ok(ApiResponse.ok("Mission đã được staff chấp nhận", response));
    }

    @PreAuthorize("@missionAuthorizationService.canRespondToMission(#id)")
    @PatchMapping("/{id}/reject-current")
    public ResponseEntity<ApiResponse<MissionResponse>> rejectCurrentStaff(
            @PathVariable String id,
            @Valid @RequestBody MissionRejectRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Mission rejected successfully",
                missionService.rejectCurrentStaffMission(id, request.getReason())));
    }

    @PatchMapping("/{id}/reject-staff")
    @PreAuthorize("@missionAuthorizationService.canRespondToMission(#id,#staffId)")
    public ResponseEntity<ApiResponse<MissionResponse>> rejectByStaffId(
            @PathVariable String id,
            @RequestParam String staffId,
            @Valid @RequestBody MissionRejectRequest request) {
        MissionResponse response = missionService.rejectMission(id, staffId, request.getReason());
        return ResponseEntity.ok(ApiResponse.ok("Mission đã bị staff từ chối", response));
    }

    /**
     * PATCH /api/missions/{id}/reject
     * Staff rejects the mission with a mandatory reason.
     * Transitions: WAITING_OPERATOR_ACCEPTANCE → PLANNING
     */
    @PatchMapping("/{id}/reject")
    @PreAuthorize("@missionAuthorizationService.canRespondToMission(#id)")
    public ResponseEntity<ApiResponse<MissionResponse>> reject(
            @PathVariable String id,
            @RequestHeader(value = "X-Staff-Id", required = false) String ignoredstaffId,
            @Valid @RequestBody MissionRejectRequest request) {
        MissionResponse response = missionService.rejectCurrentStaffMission(id, request.getReason());
        return ResponseEntity.ok(ApiResponse.ok("Mission đã bị từ chối, hệ thống sẽ phân công lại", response));
    }

    /**
     * POST /api/missions/{id}/connect
     * Confirm telemetry link with GCS app (powerOnAndPairWithGCSApp).
     * Transitions: SCHEDULED -> CONNECTED
     */
    @PostMapping("/{id}/connect")
    @PreAuthorize("@missionAuthorizationService.canOperatePayload(#id)")
    public ResponseEntity<ApiResponse<MissionResponse>> connectGcs(@PathVariable String id) {
        MissionResponse response = missionService.connectGcs(id);
        return ResponseEntity.ok(ApiResponse.ok("Telemetry link with GCS confirmed (CONNECTED)", response));
    }

    @GetMapping("/{id}/telemetry-readiness")
    @PreAuthorize("@missionAuthorizationService.canViewMission(#id)")
    public ResponseEntity<ApiResponse<MissionTelemetryReadinessResponse>> getTelemetryReadiness(
            @PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok(missionService.getTelemetryReadiness(id)));
    }

    /**
     * POST /api/missions/{id}/disconnect
     * Normal or explicit disconnection from GCS app.
     */
    @PostMapping("/{id}/disconnect")
    @PreAuthorize("@missionAuthorizationService.canOperatePayload(#id)")
    public ResponseEntity<ApiResponse<MissionResponse>> disconnectGcs(
            @PathVariable String id,
            @RequestParam(required = false, defaultValue = "NORMAL") String reason) {
        MissionResponse response = missionService.disconnectGcs(id, reason);
        return ResponseEntity.ok(ApiResponse.ok("GCS session disconnected (DISCONNECTED)", response));
    }

    /**
     * POST /api/missions/{id}/gcs-lost
     * Report GCS telemetry signal loss (LOST), triggering automatic
     * Return-To-Launch (RTL).
     */
    @PostMapping("/{id}/gcs-lost")
    @PreAuthorize("@missionAuthorizationService.canOperatePayload(#id)")
    public ResponseEntity<ApiResponse<MissionResponse>> reportGcsLost(
            @PathVariable String id,
            @RequestParam(required = false, defaultValue = "SIGNAL_LOSS") String reason) {
        MissionResponse response = missionService.handleGcsSessionLost(id, reason);
        return ResponseEntity.ok(ApiResponse.ok("GCS signal LOST – Return-To-Launch (RTL) triggered", response));
    }

    @PostMapping("/{id}/pre-device-check")
    @PreAuthorize("@missionAuthorizationService.canOperatePayload(#id)")
    public ResponseEntity<ApiResponse<PreDeviceCheckResponse>> runPreDeviceCheck(
            @PathVariable String id,
            @RequestParam String deviceId) {
        PreDeviceCheckResponse response = missionService.runPreDeviceCheck(id, deviceId);
        boolean passed = Boolean.TRUE.equals(response.getOverallPassed());
        return ResponseEntity
                .status(passed ? HttpStatus.OK.value() : 422)
                .body(ApiResponse.ok(
                        passed ? "Digital pre-device check PASSED – Flight Access Token issued"
                                : "Digital pre-device check FAILED – fault classified: " + response.getFaultType(),
                        response));
    }


    // ------------------------------------------------------------------
    // F3.2b – Bàn giao quyền điều khiển (Handover of control)
    // ------------------------------------------------------------------

    /**
     * POST /api/missions/{id}/handover
     * Staff formally accepts control of the device console.
     */
    @PostMapping("/{id}/handover")
    @PreAuthorize("@missionAuthorizationService.canOperatePayload(#id)")
    public ResponseEntity<ApiResponse<MissionResponse>> handover(
            @PathVariable String id,
            @RequestHeader(value = "X-Staff-Id", required = false) String ignoredstaffId) {
        MissionResponse response = missionService.handoverCurrentStaffControl(id);
        return ResponseEntity.ok(ApiResponse.ok("Control handed over", response));
    }

    @PreAuthorize("@missionAuthorizationService.canOperatePayload(#id)")
    @PostMapping("/{id}/handover-current")
    public ResponseEntity<ApiResponse<MissionResponse>> handoverCurrentStaff(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Mission control handed over successfully",
                missionService.handoverCurrentStaffControl(id)));
    }

    @PostMapping("/{id}/handover-staff")
    @PreAuthorize("@missionAuthorizationService.canControlFlight(#id) and @missionAuthorizationService.canViewStaffMissions(#staffId)")
    public ResponseEntity<ApiResponse<MissionResponse>> handoverByStaffId(
            @PathVariable String id,
            @RequestParam String staffId) {
        MissionResponse response = missionService.handoverControl(id, staffId);
        return ResponseEntity.ok(ApiResponse.ok("Bàn giao quyền điều khiển thành công", response));
    }

    // ------------------------------------------------------------------
    // F3.3 – Mission execution lifecycle
    // ------------------------------------------------------------------

    /**
     * POST /api/missions/{id}/start
     * Staff clicks "Press Takeoff". Validates Flight Access Token.
     */
    @PostMapping("/{id}/start")
    @PreAuthorize("@missionAuthorizationService.canControlFlight(#id)")
    public ResponseEntity<ApiResponse<MissionResponse>> start(
            @PathVariable String id,
            @RequestParam(required = false) String tokenValue) {
        MissionResponse response = missionService.startMission(id, tokenValue);
        return ResponseEntity.ok(ApiResponse.ok("Takeoff confirmed – mission IN_FLIGHT", response));
    }

    /** POST /api/missions/{id}/return – device heads back, RETURNING */
    @PostMapping("/{id}/return")
    @PreAuthorize("@missionAuthorizationService.canControlFlight(#id) or @missionAuthorizationService.canTerminateFlight(#id)")
    public ResponseEntity<ApiResponse<MissionResponse>> markReturning(@PathVariable String id) {
        MissionResponse response = missionService.markReturning(id);
        return ResponseEntity.ok(ApiResponse.ok("device đang quay trở về", response));
    }

    /** POST /api/missions/{id}/post-device or /postflight – device landed, POSTFLIGHT_CHECKING */
    @PostMapping({"/{id}/post-device", "/{id}/postflight"})
    @PreAuthorize("@missionAuthorizationService.canControlFlight(#id) or @missionAuthorizationService.canTerminateFlight(#id) or @missionAuthorizationService.canPrepareSafeStopPostflight(#id)")
    public ResponseEntity<ApiResponse<MissionResponse>> startPostflight(@PathVariable String id) {
        MissionResponse response = missionService.startPostDeviceChecking(id);
        return ResponseEntity.ok(ApiResponse.ok("Bắt đầu kiểm tra sau bay", response));
    }

    /** POST /api/missions/{id}/complete – Staff confirms complete */
    @PostMapping("/{id}/complete")
    @PreAuthorize("@missionAuthorizationService.canCompleteMission(#id)")
    public ResponseEntity<ApiResponse<MissionResponse>> complete(@PathVariable String id) {
        MissionResponse response = missionService.completeMission(id);
        return ResponseEntity.ok(ApiResponse.ok("Mission đã hoàn thành", response));
    }

    /** POST /api/missions/{id}/fail – Staff reports mission failure */
    @PostMapping("/{id}/fail")
    @PreAuthorize("@missionAuthorizationService.canControlFlight(#id)")
    public ResponseEntity<ApiResponse<MissionResponse>> fail(
            @PathVariable String id,
            @Valid @RequestBody MissionFailRequest request) {
        MissionResponse response = missionService.failMission(id, request.getReason());
        return ResponseEntity.ok(ApiResponse.ok("Mission đã báo lỗi thất bại", response));
    }

    // ------------------------------------------------------------------
    // F3.4 – Media Upload (with retry & notification on failure)
    // ------------------------------------------------------------------

    /**
     * POST /api/missions/{id}/media (Multipart)
     */
    @PostMapping(value = "/{id}/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@missionAuthorizationService.canUploadMissionMedia(#id)")
    public ResponseEntity<ApiResponse<MediaAssetResponse>> uploadMedia(
            @PathVariable String id,
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) String capturedAt,
            @RequestParam(required = false) String mediaType,
            @RequestParam("file") MultipartFile file) {
        MediaAsset saved = missionMediaUploadService.uploadWithRetry(id, deviceId, file, mediaType);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Tải media thành công", mediaAssetMapper.toResponse(saved)));
    }

    // ------------------------------------------------------------------
    // F3.5 – Post-flight status update
    // ------------------------------------------------------------------

    /**
     * PATCH /api/missions/{id}/postflight-status
     */
    @PatchMapping("/{id}/postflight-status")
    @PreAuthorize("@missionAuthorizationService.canMaintainDevice(#id)")
    public ResponseEntity<ApiResponse<MissionResponse>> updatePostFlightStatus(
            @PathVariable String id,
            @RequestParam String deviceId,
            @Valid @RequestBody PostFlightStatusRequest request) {
        MissionResponse response = missionService.recordPostFlightInspection(
                id,
                request.getNewDeviceStatus(),
                request.getNotes(),
                request.getInspectionResults(),
                request.getTelemetrySnapshot());
        return ResponseEntity.ok(ApiResponse.ok("Cập nhật trạng thái device sau bay thành công", response));
    }

    @PatchMapping("/{id}/postflight-device-status")
    @PreAuthorize("@missionAuthorizationService.canMaintainDevice(#id)")
    public ResponseEntity<ApiResponse<MissionResponse>> updatePostflightDeviceStatus(
            @PathVariable String id,
            @RequestParam com.ondemandmonitoring.device.enums.DeviceStatus status,
            @RequestParam(required = false) String notes) {
        MissionResponse response = missionService.updatePostFlightStatus(id, status, notes);
        return ResponseEntity.ok(ApiResponse.ok("Cập nhật trạng thái thiết bị sau bay thành công", response));
    }

}

