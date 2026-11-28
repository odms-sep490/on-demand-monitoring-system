package com.ondemandmonitoring.mission.workspace;

/** Opaque equality tokens, independent of command expectedVersion values. */
public record WorkspaceVersion(String missionId, String checklist, String result,
        String permissions, String media, String corrections) {}
