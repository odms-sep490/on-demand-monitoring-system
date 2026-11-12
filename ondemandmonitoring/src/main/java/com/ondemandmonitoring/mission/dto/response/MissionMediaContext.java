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
    private final java.time.Instant actualStartAt;
    private final java.time.Instant actualEndAt;

    public MissionMediaContext(String id, boolean captureAllowed) {
        this(id, captureAllowed, null);
    }

    public MissionMediaContext(String id, boolean captureAllowed, MissionStatus status) {
        this(id, captureAllowed, status, null, null);
    }

    public MissionMediaContext(String id, boolean captureAllowed, MissionStatus status,
            java.time.Instant scheduledStartAt, java.time.Instant scheduledEndAt) {
        this(id, captureAllowed, status, scheduledStartAt, scheduledEndAt, null, null);
    }

    public MissionMediaContext(String id, boolean captureAllowed, MissionStatus status,
            java.time.Instant scheduledStartAt, java.time.Instant scheduledEndAt,
            java.time.Instant actualStartAt, java.time.Instant actualEndAt) {
        this.id = id;
        this.captureAllowed = captureAllowed;
        this.status = status;
        this.scheduledStartAt = scheduledStartAt;
        this.scheduledEndAt = scheduledEndAt;
        this.actualStartAt = actualStartAt;
        this.actualEndAt = actualEndAt;
    }
}
