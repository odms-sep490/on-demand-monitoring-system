package com.ondemandmonitoring.mission.mapper;

import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.MissionDeviceAssignment;
import com.ondemandmonitoring.mission.domain.MissionStaffAssignment;
import com.ondemandmonitoring.mission.dto.response.MissionDeviceAssignmentResponse;
import com.ondemandmonitoring.mission.dto.response.MissionPlanResponse;
import com.ondemandmonitoring.mission.dto.response.MissionResponse;
import com.ondemandmonitoring.mission.dto.response.MissionStaffAssignmentResponse;
import com.ondemandmonitoring.mission.enums.MediaType;
import com.ondemandmonitoring.mission.enums.MissionStaffRole;
import com.ondemandmonitoring.mission.repository.MissionDeviceAssignmentRepository;
import com.ondemandmonitoring.mission.repository.MissionPlanRepository;
import com.ondemandmonitoring.mission.repository.MissionStaffAssignmentRepository;
import com.ondemandmonitoring.order.domain.OrderDeliverable;
import com.ondemandmonitoring.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.mapstruct.Named;
import org.mapstruct.AfterMapping;
import org.mapstruct.MappingTarget;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class MissionMapperHelper {

    private final MissionPlanRepository missionPlanRepository;
    private final MissionDeviceAssignmentRepository missionDeviceAssignmentRepository;
    private final MissionStaffAssignmentRepository missionStaffAssignmentRepository;
    private final MissionPlanMapper missionPlanMapper;
    private final MissionStaffAssignmentMapper missionStaffAssignmentMapper;
    private final MissionDeviceAssignmentMapper missionDeviceAssignmentMapper;
    private final UserRepository userRepository;

    @Named("missionLatitude")
    public Double getLatitude(Mission mission) {
        if (mission.getOrder() != null && mission.getOrder().getPoint() != null) {
            return mission.getOrder().getPoint().getY();
        }
        return null;
    }

    @Named("missionLongitude")
    public Double getLongitude(Mission mission) {
        if (mission.getOrder() != null && mission.getOrder().getPoint() != null) {
            return mission.getOrder().getPoint().getX();
        }
        return null;
    }

    @Named("missionRadiusM")
    public Double getRadiusM(Mission mission) {
        if (mission == null || mission.getOrder() == null || mission.getOrder().getDeliverables() == null) {
            return null;
        }

        return mission.getOrder().getDeliverables().stream()
                .filter(deliverable -> deliverable != null && deliverable.getRequirement() != null)
                .map(deliverable -> {
                    Double radius = toDouble(deliverable.getRequirement().get("radiusM"));
                    return radius != null ? radius : toDouble(deliverable.getRequirement().get("radius_m"));
                })
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    @Named("missionMediaType")
    public MediaType getMediaType(Mission mission) {
        if (mission == null || mission.getOrder() == null || mission.getOrder().getDeliverables() == null) {
            return null;
        }
        String text = mission.getOrder().getDeliverables().stream()
                .filter(Objects::nonNull)
                .map(this::deliverableText)
                .filter(value -> !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .reduce("", (left, right) -> left + " " + right);
        if (text.contains("stream") || text.contains("livestream") || text.contains("live stream")) {
            return MediaType.STREAMING;
        }
        if (text.contains("video") || text.contains("mp4") || text.contains("mov")) {
            return MediaType.VIDEO;
        }
        if (text.contains("photo") || text.contains("image") || text.contains("ảnh")
                || text.contains("hình") || text.contains("jpg") || text.contains("jpeg")
                || text.contains("png")) {
            return MediaType.IMAGE;
        }
        return null;
    }

    @Named("missionMediaSummary")
    public String getMediaSummary(Mission mission) {
        if (mission == null || mission.getOrder() == null || mission.getOrder().getDeliverables() == null) {
            return null;
        }
        String summary = mission.getOrder().getDeliverables().stream()
                .filter(Objects::nonNull)
                .map(this::deliverableLabel)
                .filter(value -> !value.isBlank())
                .distinct()
                .reduce((left, right) -> left + ", " + right)
                .orElse(null);
        return summary == null || summary.isBlank() ? null : summary;
    }

    @AfterMapping
    public void populateResources(Mission mission, @MappingTarget MissionResponse.MissionResponseBuilder response) {
        // Preserve existing current/history fallback exactly, but resolve it once per DTO.
        var device = getCurrentDeviceAssignment(mission).map(MissionDeviceAssignment::getDevice).orElse(null);
        if (device != null) {
            response.deviceId(device.getId()).deviceCode(device.getDeviceCode()).deviceName(device.getName())
                    .deviceSerialNumber(device.getSerialNumber())
                    .deviceStatus(device.getStatus() == null ? null : device.getStatus().name());
            var model = device.getDeviceModel();
            if (model != null) response.deviceModelCode(model.getCode()).deviceModelName(model.getModelName())
                    .deviceManufacturer(model.getManufacturer())
                    .devicePayload(firstPayloadValue(model.getSpecsMetadata()).orElse(null));
        }
        var staffId = getStaffId(mission);
        response.staffId(staffId).operatorId(staffId);
        if (mission.getOrder() != null && mission.getOrder().getExecutionPlanFinalizedBy() != null) {
            userRepository.findById(mission.getOrder().getExecutionPlanFinalizedBy()).ifPresent(manager ->
                    response.managerName(manager.getFullName())
                            .managerEmail(manager.getEmail())
                            .managerPhone(manager.getPhoneNumber()));
        }
    }

    private String getStaffId(Mission mission) {
        MissionStaffAssignment assignment = getCurrentStaffAssignment(mission, MissionStaffRole.OPERATOR)
                .or(() -> getCurrentStaffAssignment(mission, MissionStaffRole.PILOT))
                .orElse(null);
        if (assignment == null || assignment.getStaff() == null) {
            return null;
        }
        return assignment.getStaff().getId();
    }

    @Named("missionStaffAssignments")
    public List<MissionStaffAssignmentResponse> getStaffAssignments(Mission mission) {
        if (mission == null || mission.getId() == null) {
            return List.of();
        }
        var assignments = mission.getStatus() == com.ondemandmonitoring.mission.enums.MissionStatus.PLANNING
                ? missionStaffAssignmentRepository.findByMissionId(mission.getId())
                : missionStaffAssignmentRepository.findAllByMissionIdAndIsCurrentTrue(mission.getId());
        return assignments.stream()
                .sorted(Comparator.comparing(MissionStaffAssignment::getAssignedRole,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(missionStaffAssignmentMapper::toResponse)
                .toList();
    }

    @Named("missionDeviceAssignments")
    public List<MissionDeviceAssignmentResponse> getDeviceAssignments(Mission mission) {
        if (mission == null || mission.getId() == null) {
            return List.of();
        }
        var assignments = mission.getStatus() == com.ondemandmonitoring.mission.enums.MissionStatus.PLANNING
                ? missionDeviceAssignmentRepository.findByMissionId(mission.getId())
                : missionDeviceAssignmentRepository.findAllByMissionIdAndIsCurrentTrueOrderByCreatedAtDesc(mission.getId());
        return assignments.stream()
                .sorted(Comparator.comparing(MissionDeviceAssignment::getDeviceRole,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(missionDeviceAssignmentMapper::toResponse)
                .toList();
    }

    @Named("missionPlan")
    public MissionPlanResponse getPlan(Mission mission) {
        if (mission == null || mission.getId() == null) {
            return null;
        }

        return missionPlanRepository.findByMissionId(mission.getId())
                .map(missionPlanMapper::toPlanResponse)
                .orElse(null);
    }

    private Optional<MissionDeviceAssignment> getCurrentDeviceAssignment(Mission mission) {
        if (mission == null || mission.getId() == null) {
            return Optional.empty();
        }
        List<MissionDeviceAssignment> current =
                missionDeviceAssignmentRepository.findAllByMissionIdAndIsCurrentTrueOrderByCreatedAtDesc(mission.getId());
        return !current.isEmpty()
                ? Optional.of(current.get(0))
                : missionDeviceAssignmentRepository.findFirstByMissionIdOrderByCreatedAtDesc(mission.getId());
    }

    private Optional<MissionStaffAssignment> getCurrentStaffAssignment(Mission mission, MissionStaffRole role) {
        if (mission == null || mission.getId() == null) {
            return Optional.empty();
        }
        return missionStaffAssignmentRepository.findAllByMissionIdAndAssignedRoleAndIsCurrentTrue(mission.getId(), role)
                .stream()
                .findFirst();
    }

    private Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Optional<String> firstPayloadValue(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Optional.empty();
        }
        return List.of("payloadCapabilities", "payload", "payloads", "camera", "sensor")
                .stream()
                .map(metadata::get)
                .map(this::metadataValue)
                .filter(value -> !value.isBlank())
                .findFirst();
    }

    private String metadataValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder builder = new StringBuilder();
            for (Object item : iterable) {
                String text = metadataValue(item);
                if (!text.isBlank()) {
                    if (!builder.isEmpty()) {
                        builder.append(", ");
                    }
                    builder.append(text);
                }
            }
            return builder.toString();
        }
        return String.valueOf(value).trim();
    }

    private String deliverableLabel(OrderDeliverable deliverable) {
        if (deliverable.getDeliverableType() == null) {
            return "";
        }
        String name = deliverable.getDeliverableType().getName();
        String format = deliverable.getDeliverableType().getDefaultFormat();
        if (format == null || format.isBlank()) {
            return name == null ? "" : name;
        }
        return (name == null || name.isBlank()) ? format : name + " (" + format + ")";
    }

    private String deliverableText(OrderDeliverable deliverable) {
        if (deliverable.getDeliverableType() == null) {
            return "";
        }
        return (deliverable.getDeliverableType().getName() == null ? "" : deliverable.getDeliverableType().getName())
                + " "
                + (deliverable.getDeliverableType().getDefaultFormat() == null ? "" : deliverable.getDeliverableType().getDefaultFormat());
    }
}
