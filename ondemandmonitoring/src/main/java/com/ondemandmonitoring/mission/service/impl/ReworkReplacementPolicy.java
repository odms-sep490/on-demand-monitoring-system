package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.enums.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * A failed attempt remains history; only completed work from the same correction can replace it.
 */
public final class ReworkReplacementPolicy {
  private ReworkReplacementPolicy() {}

  public static boolean replaced(
      Mission failed, List<Mission> missions, List<MissionChecklistAssignment> assignments) {
    if (failed.getPurpose() != MissionPurpose.REWORK || failed.getStatus() != MissionStatus.FAILED)
      return false;
    var required = scope(failed, assignments);
    if (required.isEmpty()) return false;
    var completed =
        missions.stream()
            .filter(
                m ->
                    m.getStatus() == MissionStatus.COMPLETED
                        && ExecutionPlanCoverageService.effective(m)
                        && m.getPurpose() == MissionPurpose.REWORK
                        && sameOrigin(failed, m)
                        && failed.getCreatedAt() != null
                        && m.getCreatedAt() != null
                        && m.getCreatedAt().isAfter(failed.getCreatedAt()))
            .map(Mission::getId)
            .collect(Collectors.toSet());
    var covered =
        assignments.stream()
            .filter(a -> a.getRemovedAt() == null && completed.contains(a.getMissionId()))
            .map(MissionChecklistAssignment::getOrderChecklistItemId)
            .collect(Collectors.toSet());
    return covered.containsAll(required);
  }

  private static Set<String> scope(Mission mission, List<MissionChecklistAssignment> assignments) {
    return assignments.stream()
        .filter(a -> a.getRemovedAt() == null && mission.getId().equals(a.getMissionId()))
        .map(MissionChecklistAssignment::getOrderChecklistItemId)
        .collect(Collectors.toSet());
  }

  private static boolean sameOrigin(Mission left, Mission right) {
    if (!left.getOrder().getId().equals(right.getOrder().getId())) return false;
    if (left.getInspectionCorrection() != null)
      return left.getInspectionCorrection().getId() != null
          && right.getInspectionCorrection() != null
          && Objects.equals(
              left.getInspectionCorrection().getId(), right.getInspectionCorrection().getId())
          && left.getResultRevisionCaseId() == null
          && right.getResultRevisionCaseId() == null;
    return right.getInspectionCorrection() == null
        && left.getResultRevisionCaseId() != null
        && left.getResultRevisionCaseId().equals(right.getResultRevisionCaseId());
  }
}
