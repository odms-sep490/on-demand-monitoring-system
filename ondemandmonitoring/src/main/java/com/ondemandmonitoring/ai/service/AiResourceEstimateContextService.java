package com.ondemandmonitoring.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.checklist.domain.ChecklistDeviceRequirement;
import com.ondemandmonitoring.checklist.repository.ChecklistDeviceRequirementRepository;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.MissionChecklistAssignment;
import com.ondemandmonitoring.mission.repository.MissionChecklistAssignmentRepository;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads and serializes only the order/checklist data needed by the AI prompt. */
@Service
public class AiResourceEstimateContextService {
    private final ChecklistDeviceRequirementRepository deviceRequirements;
    private final MissionRepository missions;
    private final MissionChecklistAssignmentRepository missionChecklistAssignments;
    private final ObjectMapper objectMapper;

    public AiResourceEstimateContextService(
            ChecklistDeviceRequirementRepository deviceRequirements,
            MissionRepository missions,
            MissionChecklistAssignmentRepository missionChecklistAssignments,
            ObjectMapper objectMapper) {
        this.deviceRequirements = deviceRequirements;
        this.missions = missions;
        this.missionChecklistAssignments = missionChecklistAssignments;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public EstimateContext buildContext(String missionId) {
        Mission mission = missions.findByIdWithOrder(missionId)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        Order order = mission.getOrder();
        List<OrderChecklistItem> assignedItems = missionChecklistAssignments
                .findByMissionIdAndRemovedAtIsNullOrderByDisplayOrderAscIdAsc(missionId).stream()
                .map(MissionChecklistAssignment::getOrderChecklistItem)
                .toList();

        Map<String, List<DeviceCapability>> capabilitiesByChecklist = new LinkedHashMap<>();
        List<String> checklistIds = assignedItems.stream()
                .map(OrderChecklistItem::getSourceChecklist)
                .filter(Objects::nonNull)
                .map(definition -> definition.getId())
                .distinct()
                .toList();
        if (!checklistIds.isEmpty()) {
            for (ChecklistDeviceRequirement requirement : deviceRequirements.findAllByChecklistIds(checklistIds)) {
                var type = requirement.getDeviceType();
                capabilitiesByChecklist.computeIfAbsent(
                                requirement.getChecklistDefinition().getId(), ignored -> new ArrayList<>())
                        .add(new DeviceCapability(type.getCode(), type.getName(), type.getDescription()));
            }
        }

        List<ChecklistInput> checklistInputs = assignedItems.stream()
                .map(item -> toChecklistInput(item, capabilitiesByChecklist))
                .toList();
        var preferredTime = order.getPreferredTime();
        var input = new EstimateInput(
                mission.getScopeLabel(),
                order.getService().getName(),
                order.getDescription(),
                order.getUsagePurpose() == null ? null : order.getUsagePurpose().name(),
                order.getFlightPurpose() == null ? null : order.getFlightPurpose().name(),
                order.getAltitudeM(),
                order.getEstimatedLengthM(),
                order.getEstimatedLengthM() == null ? null
                        : BigDecimal.valueOf(order.getEstimatedLengthM()).divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP),
                areaHectares(order.getTargetArea()),
                order.getPreferredDateFrom(),
                order.getPreferredDateTo(),
                preferredTime == null ? null : preferredTime.getName(),
                preferredTime == null ? null : preferredTime.getStartTime(),
                preferredTime == null ? null : preferredTime.getEndTime(),
                order.getRecurrenceType() == null ? null : order.getRecurrenceType().name(),
                order.getRecurrenceOccurrences(),
                checklistInputs);

        String userPrompt = "Hãy ước lượng nhu cầu Drone cho mission sau. Chỉ dùng thông tin Order và "
                + "các Checklist đã gán cho Mission. Không chọn ngày, không chọn ID tài nguyên và không đánh giá "
                + "availability. Trả về một JSON object hợp lệ, không markdown, với các trường: "
                + "minimumSuggestedDevices (số nguyên >= 1), recommendedDevices (số nguyên >= minimumSuggestedDevices), "
                + "reason (string), assumptions (array string), warnings (array string). Nêu rõ giả định khi thiếu dữ liệu.\n\n"
                + objectMapper.valueToTree(input).toPrettyString();
        return new EstimateContext(order.getId(), userPrompt);
    }

    private ChecklistInput toChecklistInput(
            OrderChecklistItem item,
            Map<String, List<DeviceCapability>> capabilitiesByChecklist) {
        String checklistId = item.getSourceChecklist() == null ? null : item.getSourceChecklist().getId();
        return new ChecklistInput(
                item.getContent(),
                item.getEffectiveDescription(),
                item.getEffectiveExecutionSteps(),
                item.getEffectiveInputRequirements(),
                item.getEffectiveExpectedOutputs(),
                item.getDifficultySnapshot() == null ? null : item.getDifficultySnapshot().name(),
                item.getMinimumEvidenceCount(),
                item.getMinimumImageCount(),
                item.getMinimumVideoCount(),
                item.getRequestedImageCount(),
                item.getRequestedVideoCount(),
                item.getStandardVideoDurationSnapshot(),
                checklistId == null ? List.of() : capabilitiesByChecklist.getOrDefault(checklistId, List.of()));
    }

    /** Uses the same local projection used by the pricing engine to report polygon size in hectares. */
    private BigDecimal areaHectares(Polygon polygon) {
        if (polygon == null) return null;
        Coordinate[] points = polygon.getExteriorRing().getCoordinates();
        if (points.length < 4) return BigDecimal.ZERO;
        double latitude = java.util.Arrays.stream(points).mapToDouble(point -> point.y).average().orElse(0);
        double scaleX = 111_320d * Math.cos(Math.toRadians(latitude));
        double scaleY = 110_540d;
        double twiceArea = 0;
        for (int i = 0; i < points.length - 1; i++) {
            twiceArea += points[i].x * scaleX * points[i + 1].y * scaleY
                    - points[i + 1].x * scaleX * points[i].y * scaleY;
        }
        return BigDecimal.valueOf(Math.abs(twiceArea) / 2d / 10_000d).setScale(3, RoundingMode.HALF_UP);
    }

    private record EstimateInput(
            String missionScopeLabel,
            String serviceName,
            String orderDescription,
            String usagePurpose,
            String flightPurpose,
            Double altitudeM,
            Double estimatedLengthM,
            BigDecimal estimatedLengthKm,
            BigDecimal targetAreaHa,
            java.time.LocalDate preferredDateFrom,
            java.time.LocalDate preferredDateTo,
            String preferredTime,
            java.time.LocalTime fixedStartTime,
            java.time.LocalTime fixedEndTime,
            String recurrenceType,
            Integer recurrenceOccurrences,
            List<ChecklistInput> checklists) {
    }

    private record ChecklistInput(
            String content,
            String description,
            List<String> executionSteps,
            List<String> inputRequirements,
            List<String> expectedOutputs,
            String difficulty,
            int minimumEvidenceCount,
            int minimumImageCount,
            int minimumVideoCount,
            int requestedImageCount,
            int requestedVideoCount,
            Integer standardVideoDurationSeconds,
            List<DeviceCapability> requiredDeviceTypes) {
    }

    private record DeviceCapability(String code, String name, String description) {
    }

    public record EstimateContext(String orderId, String userPrompt) {
    }
}
