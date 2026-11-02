package com.ondemandmonitoring.mission.dto.response;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.FieldDefaults;

import java.time.Instant;

@Getter
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class MissionResourceReplacementResponse {
    String resourceType;
    String resourceRole;
    String previousAssignmentId;
    String previousResourceId;
    String previousResourceName;
    String previousResourceCode;
    String replacementAssignmentId;
    String replacementResourceId;
    String replacementResourceName;
    String replacementResourceCode;
    String reason;
    Instant replacedAt;
}
