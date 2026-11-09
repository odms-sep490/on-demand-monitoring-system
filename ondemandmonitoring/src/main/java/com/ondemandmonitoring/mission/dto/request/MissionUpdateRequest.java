package com.ondemandmonitoring.mission.dto.request;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
public class MissionUpdateRequest {
    private List<String> checklistIds;

    private Instant scheduledStartAt;
    private Instant scheduledEndAt;
}
