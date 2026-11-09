package com.ondemandmonitoring.mission.dto.response;

import com.ondemandmonitoring.media.dto.response.MediaResponse;
import com.ondemandmonitoring.mission.enums.MissionResultApprovalStatus;
import com.ondemandmonitoring.mission.enums.MissionResultStatus;
import java.time.Instant;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class MissionResultResponse {

    String id;
    String missionId;
    String missionCode;
    MissionResultStatus status;
    MissionResultApprovalStatus approvalStatus;
    boolean inspectorConfirmed;
    Instant startedAt;
    Instant endedAt;
    Instant completedAt;
    Instant submittedAt;
    Instant approvedAt;
    Instant rejectedAt;
    Long durationSeconds;
    Integer mediaCount;
    String summary;
    String notes;
    String createdBy;
    String reviewedBy;
    String reviewNote;
    Instant createdAt;
    Instant updatedAt;
    List<MediaResponse> mediaFiles;
}
