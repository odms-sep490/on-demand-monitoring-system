package com.ondemandmonitoring.mission.mapper;

import com.ondemandmonitoring.mission.domain.MissionPlan;
import com.ondemandmonitoring.mission.domain.PlanWaypoint;
import com.ondemandmonitoring.mission.dto.response.MissionPlanResponse;
import com.ondemandmonitoring.mission.dto.response.PlanWaypointResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.Comparator;
import java.util.List;

@Mapper(componentModel = "spring")
public interface MissionPlanMapper {

    @Mapping(target = "estimatedRemainingBatteryPercent", source = ".", qualifiedByName = "planRemainingBattery")
    @Mapping(target = "waypoints", source = "waypoints", qualifiedByName = "planSortedWaypoints")
    MissionPlanResponse toPlanResponse(MissionPlan plan);

    PlanWaypointResponse toWaypointResponse(PlanWaypoint waypoint);

    @Named("planSortedWaypoints")
    default List<PlanWaypointResponse> sortedWaypoints(List<PlanWaypoint> waypoints) {
        if (waypoints == null) {
            return List.of();
        }
        return waypoints.stream()
                .sorted(Comparator.comparing(PlanWaypoint::getSequence))
                .map(this::toWaypointResponse)
                .toList();
    }

    @Named("planRemainingBattery")
    default Double estimatedRemainingBatteryPercent(MissionPlan plan) {
        if (plan.getAvailableBatteryPercentAtPlanning() == null
                || plan.getEstimatedBatteryUsedPercent() == null) {
            return null;
        }
        double remaining = plan.getAvailableBatteryPercentAtPlanning() - plan.getEstimatedBatteryUsedPercent();
        return Math.max(0.0, Math.min(100.0, remaining));
    }
}
