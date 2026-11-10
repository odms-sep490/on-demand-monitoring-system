package com.ondemandmonitoring.delivery.dto.response;

import com.ondemandmonitoring.delivery.enums.DeliveryStatus;

public record MissionDeliverySummary(String missionId, String missionCode, DeliveryStatus status, java.util.List<String> relatedMissionIds) {}
