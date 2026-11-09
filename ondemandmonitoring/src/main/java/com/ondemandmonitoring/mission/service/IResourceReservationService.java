package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.mission.domain.Mission;
import java.time.Instant;
import java.util.Collection;

public interface IResourceReservationService {
    void lockResources(Collection<String> resourceIds);
    void lockMissionResources(String missionId, Collection<String> additionalResourceIds);
    void reserve(Mission mission, String resourceId, boolean staff);
    void reserveAll(Mission mission, java.util.Map<String, Boolean> resources);
    void revalidateSchedule(Mission mission, Instant start, Instant end);
    void release(String missionId, String reason);
    void releaseResource(String missionId, String resourceId, String reason);
}
