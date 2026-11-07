package com.ondemandmonitoring.mission.dto.request;

import com.ondemandmonitoring.mission.enums.MissionResultStatus;
import java.time.Instant;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Data;
import lombok.experimental.FieldDefaults;

@Data
@FieldDefaults(level = AccessLevel.PRIVATE)
public class MissionResultRequest {

    MissionResultStatus status;
    Instant startedAt;
    Instant endedAt;
    Instant completedAt;
    @Size(max = 1000)
    String summary;
    String notes;
    String reviewedBy;
}
