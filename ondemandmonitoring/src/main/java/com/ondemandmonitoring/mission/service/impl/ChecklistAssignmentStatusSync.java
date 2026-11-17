package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.repository.MissionChecklistAssignmentRepository;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.enums.ChecklistAssignmentStatus;
import com.ondemandmonitoring.order.repository.OrderChecklistItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Keeps {@link OrderChecklistItem#getAssignmentStatus()} in sync with the lifecycle of the
 * effective assignments that own the item, including retained original work. Must run inside the
 * caller's transaction.
 */
@Component
@RequiredArgsConstructor
public class ChecklistAssignmentStatusSync {
  private final MissionChecklistAssignmentRepository assignments;
  private final OrderChecklistItemRepository items;

  /** Recompute aggregate ownership; a cancelled rework must not downgrade original work. */
  public void syncForMission(Mission mission) {
    if (targetFor(mission.getStatus()) == null) return;
    var affected =
        assignments.findByMissionId(mission.getId()).stream()
            .map(a -> a.getOrderChecklistItem())
            .filter(java.util.Objects::nonNull)
            .collect(
                java.util.stream.Collectors.toMap(OrderChecklistItem::getId, i -> i, (a, b) -> a));
    var active = assignments.findActiveByOrderId(mission.getOrder().getId());
    recompute(mission, affected.values(), active);
    items.saveAll(affected.values());
  }

  static void recompute(
      Mission mission,
      java.util.Collection<OrderChecklistItem> affected,
      java.util.List<com.ondemandmonitoring.mission.domain.MissionChecklistAssignment> active) {
    for (var item : affected) {
      var target = ChecklistAssignmentStatus.UNASSIGNED;
      for (var assignment : active) {
        if (!item.getId().equals(assignment.getOrderChecklistItemId())) continue;
        var owner =
            assignment.getMissionId().equals(mission.getId()) ? mission : assignment.getMission();
        if (!ChecklistAssignmentIntegrity.matches(owner, assignment))
          throw new com.ondemandmonitoring.common.exception.ApiException(
              com.ondemandmonitoring.common.exception.ErrorCode.CHECKLIST_ASSIGNMENT_CROSS_ORDER);
        if (!ExecutionPlanCoverageService.effective(owner)) continue;
        var candidate = targetFor(owner.getStatus());
        if (candidate == null) candidate = ChecklistAssignmentStatus.ASSIGNED;
        if (rank(candidate) > rank(target)) target = candidate;
      }
      item.setAssignmentStatus(target);
    }
  }

  private static int rank(ChecklistAssignmentStatus status) {
    return switch (status) {
      case COMPLETED -> 3;
      case IN_PROGRESS -> 2;
      case ASSIGNED -> 1;
      default -> 0;
    };
  }

  static ChecklistAssignmentStatus targetFor(MissionStatus status) {
    if (status == null) return null;
    return switch (status) {
      case IN_PROGRESS -> ChecklistAssignmentStatus.IN_PROGRESS;
      case COMPLETED -> ChecklistAssignmentStatus.COMPLETED;
      // A failed Mission leaves its order checklist eligible for a replacement flight.
      case FAILED -> ChecklistAssignmentStatus.ASSIGNED;
      // Cancelled Missions are no longer effective: release items for re-assignment / replacement.
      case CANCELLED -> ChecklistAssignmentStatus.UNASSIGNED;
      default -> null;
    };
  }
}
