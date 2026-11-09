package com.ondemandmonitoring.mission.dto.response;

import com.ondemandmonitoring.mission.enums.MissionStatus;
import lombok.Getter;

/** Public, immutable mission context; does not expose persistence entities. */
@Getter
public final class MissionMediaContext {
    private final String id;
    private final boolean captureAllowed;
    private final MissionStatus status;
    private final java.time.Instant scheduledStartAt;
    private final java.time.Instant scheduledEndAt;

    public MissionMediaContext(String id, boolean captureAllowed) {
        this(id, captureAllowed, null);
    }

    public MissionMediaContext(String id, boolean captureAllowed, MissionStatus status) {
        this(id, captureAllowed, status, null, null);
    }

    public MissionMediaContext(String id, boolean captureAllowed, MissionStatus status,
            java.time.Instant scheduledStartAt, java.time.Instant scheduledEndAt) {
        this.id = id;
        this.captureAllowed = captureAllowed;
        this.status = status;
        this.scheduledStartAt = scheduledStartAt;
        this.scheduledEndAt = scheduledEndAt;
    }
}
