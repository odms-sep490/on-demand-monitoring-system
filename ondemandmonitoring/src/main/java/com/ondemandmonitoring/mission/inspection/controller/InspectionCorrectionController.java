package com.ondemandmonitoring.mission.inspection.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.mission.inspection.dto.request.InspectionCorrectionDecision;
import com.ondemandmonitoring.mission.inspection.dto.request.InspectionCorrectionRequest;
import com.ondemandmonitoring.mission.inspection.dto.request.InternalCorrectionMissionRequest;
import com.ondemandmonitoring.mission.inspection.dto.response.InspectionCorrectionResponse;
import com.ondemandmonitoring.mission.inspection.service.IInspectionCorrectionService;
import com.ondemandmonitoring.mission.inspection.service.IInternalInspectionMissionService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/missions/{missionId}/inspection-corrections")
@PreAuthorize("@missionAuthorizationService.canViewMission(#missionId)")
public class InspectionCorrectionController {
  private final IInspectionCorrectionService service;
  private final IInternalInspectionMissionService missions;

  @PostMapping("/{id}/missions")
  @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
  public ApiResponse<com.ondemandmonitoring.mission.dto.response.MissionResponse> createMission(
      @PathVariable String missionId,
      @PathVariable String id,
      @Valid @RequestBody InternalCorrectionMissionRequest request) {
    return ApiResponse.ok(missions.create(missionId, id, request));
  }

  @PostMapping("/{id}/finalize-plan")
  @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
  public ApiResponse<com.ondemandmonitoring.mission.dto.response.MissionResponse> finalizePlan(
      @PathVariable String missionId,
      @PathVariable String id,
      @Valid @RequestBody
          com.ondemandmonitoring.mission.dto.request.ExecutionPlanFinalizeRequest request) {
    return ApiResponse.ok(missions.finalizePlan(missionId, id, request));
  }

  @GetMapping
  public ApiResponse<List<InspectionCorrectionResponse>> list(@PathVariable String missionId) {
    return ApiResponse.ok(service.list(missionId));
  }

  @PostMapping
  @PreAuthorize("@missionAuthorizationService.canAssessMonitoringChecklist(#missionId)")
  public ApiResponse<InspectionCorrectionResponse> request(
      @PathVariable String missionId, @Valid @RequestBody InspectionCorrectionRequest request) {
    return ApiResponse.ok(service.request(missionId, request));
  }

  @PostMapping("/{id}/resubmit")
  @PreAuthorize("@missionAuthorizationService.canAttachChecklistEvidence(#missionId)")
  public ApiResponse<InspectionCorrectionResponse> resubmit(
      @PathVariable String missionId,
      @PathVariable String id,
      @Valid @RequestBody InspectionCorrectionDecision request) {
    return ApiResponse.ok(service.resubmit(missionId, id, request));
  }

  @PostMapping("/{id}/resolve")
  @PreAuthorize("@missionAuthorizationService.canAssessMonitoringChecklist(#missionId)")
  public ApiResponse<InspectionCorrectionResponse> resolve(
      @PathVariable String missionId,
      @PathVariable String id,
      @Valid @RequestBody InspectionCorrectionDecision request) {
    return ApiResponse.ok(service.resolve(missionId, id, request));
  }

  @PostMapping("/{id}/return")
  @PreAuthorize("@missionAuthorizationService.canAssessMonitoringChecklist(#missionId)")
  public ApiResponse<InspectionCorrectionResponse> returnToStaff(
      @PathVariable String missionId,
      @PathVariable String id,
      @Valid @RequestBody InspectionCorrectionDecision request) {
    return ApiResponse.ok(service.returnToStaff(missionId, id, request));
  }
}
