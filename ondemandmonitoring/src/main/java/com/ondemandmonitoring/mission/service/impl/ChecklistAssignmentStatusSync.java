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
 * single effective Mission that owns the item. Must run inside the caller's transaction.
 */
@Component
@RequiredArgsConstructor
public class ChecklistAssignmentStatusSync {
    private final MissionChecklistAssignmentRepository assignments;
    private final OrderChecklistItemRepository items;

    /** Maps the Mission status to item status; statuses without a mapping leave items untouched. */
    public void syncForMission(Mission mission) {
        ChecklistAssignmentStatus target = targetFor(mission.getStatus());
        if (target == null) return;
        var owned = assignments.findByMissionIdAndRemovedAtIsNullOrderByDisplayOrderAscIdAsc(mission.getId()).stream()
                .map(a -> a.getOrderChecklistItem()).toList();
        owned.forEach(item -> item.setAssignmentStatus(target));
        items.saveAll(owned);
    }

    static ChecklistAssignmentStatus targetFor(MissionStatus status) {
        if (status == null) return null;
        return switch (status) {
            case IN_PROGRESS -> ChecklistAssignmentStatus.IN_PROGRESS;
            case COMPLETED -> ChecklistAssignmentStatus.COMPLETED;
            // Cancelled Missions are no longer effective: release items for re-assignment / replacement.
            case CANCELLED -> ChecklistAssignmentStatus.UNASSIGNED;
            default -> null;
        };
    }
}
