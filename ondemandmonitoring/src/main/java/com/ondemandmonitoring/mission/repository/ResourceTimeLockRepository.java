package com.ondemandmonitoring.mission.repository;

import com.ondemandmonitoring.mission.domain.ResourceTimeLock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ResourceTimeLockRepository extends JpaRepository<ResourceTimeLock, String> {

    List<ResourceTimeLock> findByMissionId(String missionId);

    @org.springframework.data.jpa.repository.Query("""
            select distinct l.resourceId from ResourceTimeLock l where l.resourceId in :resourceIds
              and l.mission.id<>:missionId and l.releasedAt is null
              and l.startTime<:end and l.endTime>:start
            """)
    java.util.Set<String> findConflictingResourceIds(
            @org.springframework.data.repository.query.Param("resourceIds") java.util.Collection<String> resourceIds,
            @org.springframework.data.repository.query.Param("missionId") String missionId,
            @org.springframework.data.repository.query.Param("start") java.time.Instant start,
            @org.springframework.data.repository.query.Param("end") java.time.Instant end);

    @org.springframework.data.jpa.repository.Query("""
            select count(l) > 0 from ResourceTimeLock l where l.resourceId=:resourceId
              and l.mission.id<>:missionId and l.releasedAt is null
              and l.startTime<:end and l.endTime>:start
            """)
    boolean existsActiveOverlap(@org.springframework.data.repository.query.Param("resourceId") String resourceId,
            @org.springframework.data.repository.query.Param("missionId") String missionId,
            @org.springframework.data.repository.query.Param("start") java.time.Instant start,
            @org.springframework.data.repository.query.Param("end") java.time.Instant end);

    List<ResourceTimeLock> findAllByResourceIdAndMissionId(String resourceId, String missionId);
}
