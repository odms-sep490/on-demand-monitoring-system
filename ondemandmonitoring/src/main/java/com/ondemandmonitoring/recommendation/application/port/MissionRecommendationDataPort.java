package com.ondemandmonitoring.recommendation.application.port;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Read-only boundary into Mission, Order, checklist requirements, and resource availability. */
public interface MissionRecommendationDataPort {
    MissionScope getScope(String missionId);

    List<DeviceCandidate> findAvailableDevices(String missionId, Instant start, Instant end);

    int countAvailableStaff(String missionId, Instant start, Instant end);

    record MissionScope(
            String missionId,
            String status,
            boolean cancelled,
            long executionPlanRevision,
            long scheduleRevision,
            LocalDate preferredDateFrom,
            LocalDate preferredDateTo,
            LocalTime fixedStartTime,
            LocalTime fixedEndTime,
            List<ChecklistCapability> checklistCapabilities) {
    }

    record ChecklistCapability(String checklistItemId, String title, List<String> requiredCapabilities) {
    }

    record DeviceCandidate(String id, String code, String name, List<String> capabilityCodes) {
    }
}
