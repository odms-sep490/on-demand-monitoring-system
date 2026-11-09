package com.ondemandmonitoring.mission.service;
/** Mission-owned coordination boundary used by media workflow commands. */
public interface IMissionMediaEvidenceGuard {
    void lock(String mediaId);
    void lockMission(String missionId);
    void requireMutable(String missionId, String mediaId);
    void requireDeletable(String mediaId);
    boolean hasEvidenceOrResultReferences(String mediaId);
}
