package com.ondemandmonitoring.mission.inspection.dto.response;

import com.ondemandmonitoring.mission.inspection.enums.InspectionCorrectionStatus;
import java.time.Instant;
import java.util.List;

public record InspectionCorrectionResponse(
    String id,
    String missionId,
    long version,
    InspectionCorrectionStatus status,
    String reviewToken,
    String requestedBy,
    Instant requestedAt,
    Instant resolvedAt,
    String supplementalMissionId,
    String supplementalMissionCode,
    String supplementalMissionStatus,
    List<Item> items,
    List<Event> events) {
  public record Item(
      String executionId,
      String content,
      long requestedExecutionVersion,
      String reason,
      List<String> mediaIds) {}

  public record Event(String action, String actor, Instant occurredAt, String note) {}
}
