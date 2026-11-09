package com.ondemandmonitoring.mission.dto.request;

import lombok.AccessLevel;
import lombok.Data;
import lombok.experimental.FieldDefaults;

@Data
@FieldDefaults(level = AccessLevel.PRIVATE)
public class MissionResultReviewRequest {

    String reviewedBy;
    String note;
}
