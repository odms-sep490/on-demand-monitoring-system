package com.ondemandmonitoring.mission.inspection.service.impl;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.inspection.domain.InspectionCorrection;
import com.ondemandmonitoring.mission.inspection.repository.InspectionCorrectionItemRepository;
import com.ondemandmonitoring.mission.inspection.repository.InspectionCorrectionRepository;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InspectionCorrectionPolicy {
  private final InspectionCorrectionRepository corrections;
  private final InspectionCorrectionItemRepository items;
  private final MissionChecklistExecutionRepository executions;
  private final MissionResultRepository results;
  private final IChecklistEvidenceService evidence;
  private final IMissionChecklistScopeService scope;

  public boolean hasOpen(String missionId) {
    return corrections.existsByActiveMissionId(missionId);
  }

  public void requireResolved(String missionId) {
    if (corrections.existsByActiveMissionId(missionId))
      throw new ApiException(ErrorCode.INSPECTION_CORRECTION_PENDING);
  }

  /**
   * Delegation is explicit, exact checklist scoped, Inspector approved, and never inferred from
   * siblings.
   */
  public Map<String, MissionChecklistExecution> delegatedExecutions(String missionId) {
    var delegated = new LinkedHashMap<String, MissionChecklistExecution>();
    var byCorrection = new HashMap<String, Map<String, MissionChecklistExecution>>();
    for (var item : items.findResolvedDelegations(missionId)) {
      var replacement =
          byCorrection
              .computeIfAbsent(
                  item.getCorrection().getId(),
                  ignored -> {
                    var child = acceptedSupplement(item.getCorrection());
                    return executions.findOrderedByMissionId(child.getId()).stream()
                        .collect(
                            java.util.stream.Collectors.toMap(
                                MissionChecklistExecution::getOrderChecklistItemId, e -> e));
                  })
              .get(item.getExecution().getOrderChecklistItemId());
      if (replacement == null)
        throw new ApiException(ErrorCode.CHECKLIST_EXECUTION_INTEGRITY_INVALID);
      delegated.put(item.getExecution().getId(), replacement);
    }
    return delegated;
  }

  public Mission acceptedSupplement(InspectionCorrection correction) {
    var child = correction.getSupplementalMission();
    if (child == null
        || child.getInspectionCorrection() == null
        || !correction.getId().equals(child.getInspectionCorrection().getId())
        || child.getResultRevisionCaseId() != null
        || child.getPurpose() != MissionPurpose.REWORK
        || child.getStatus() != MissionStatus.COMPLETED
        || child.getCancellationAt() != null
        || !child.getOrder().getId().equals(correction.getMission().getOrder().getId()))
      throw new ApiException(ErrorCode.INSPECTION_CORRECTION_PENDING);
    var result =
        results
            .findByMissionId(child.getId())
            .orElseThrow(() -> new ApiException(ErrorCode.INSPECTION_CORRECTION_PENDING));
    if (result.getApprovalStatus() != MissionResultApprovalStatus.APPROVED
        || !result.isInspectorConfirmed()
        || result.getStatus() == MissionResultStatus.FAILED)
      throw new ApiException(ErrorCode.INSPECTION_CORRECTION_PENDING);
    scope.requireMaterialized(child);
    var requested =
        items.findItems(correction.getId()).stream()
            .map(i -> i.getExecution().getOrderChecklistItemId())
            .collect(java.util.stream.Collectors.toSet());
    var actual = executions.findOrderedByMissionId(child.getId());
    var links = evidence.active(child.getId());
    if (actual.size() != requested.size()
        || !actual.stream()
            .map(MissionChecklistExecution::getOrderChecklistItemId)
            .collect(java.util.stream.Collectors.toSet())
            .equals(requested)
        || actual.stream().anyMatch(e -> !evidence.readyForInspectorApproval(e, links)))
      throw new ApiException(ErrorCode.CHECKLIST_NOT_READY);
    return child;
  }

  public List<MissionChecklistEvidence> selectedSupplementalEvidence(String missionId) {
    var byMission =
        delegatedExecutions(missionId).values().stream()
            .collect(
                java.util.stream.Collectors.groupingBy(MissionChecklistExecution::getMissionId));
    var selected = new ArrayList<MissionChecklistEvidence>();
    byMission.forEach(
        (id, rows) -> {
          var executionIds =
              rows.stream()
                  .map(MissionChecklistExecution::getId)
                  .collect(java.util.stream.Collectors.toSet());
          evidence.active(id).stream()
              .filter(
                  link ->
                      link.isSelectedForCustomer()
                          && executionIds.contains(link.getMissionChecklistExecution().getId()))
              .forEach(selected::add);
        });
    return selected;
  }
}
