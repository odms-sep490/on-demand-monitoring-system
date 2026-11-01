package com.ondemandmonitoring.media.repository;

import com.ondemandmonitoring.media.domain.MediaAsset;
import java.util.List;
import java.util.Optional;
import com.ondemandmonitoring.media.enums.MediaStorageStatus;
import com.ondemandmonitoring.media.enums.MediaStatus;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, String> {
        @Query("select m.id from MediaAsset m where m.orphanCleanupRequestedAt <= :before and m.storageStatus <> com.ondemandmonitoring.media.enums.MediaStorageStatus.DELETED order by m.orphanCleanupRequestedAt, m.id")
        List<String> findOrphanCleanupCandidates(@Param("before") Instant before, Pageable pageable);
        @Query("""
                select m.id from MediaAsset m
                where m.mission.order.resultExpiresAt is not null
                  and m.mission.order.resultExpiresAt <= :now
                  and m.storageStatus in :statuses
                order by m.mission.order.resultExpiresAt, m.id
                """)
        Page<String> findExpiredStorageCandidateIds(
                        @Param("now") Instant now,
                        @Param("statuses") List<MediaStorageStatus> statuses,
                        Pageable pageable);
        @Query("select m from MediaAsset m where m.mission.id = :missionId order by m.capturedAt desc, m.id")
        Page<MediaAsset> findMissionCandidates(String missionId, Pageable pageable);
        @Query("select m.mission.id from MediaAsset m where m.id = :id")
        Optional<String> findMissionId(String id);

        @Query("select count(m) from MediaAsset m where m.mission.id = :missionId and m.mediaStatus = :status")
        long countByMissionIdAndMediaStatus(String missionId, MediaStatus status);

        @Query("select count(m) from MediaAsset m where m.mission.id = :missionId and m.mediaStatus in :statuses")
        long countByMissionIdAndMediaStatusIn(String missionId, List<MediaStatus> statuses);

        @Query("select m from MediaAsset m where m.mission.id = :missionId and m.mediaStatus = :status")
        Page<MediaAsset> findByMissionIdAndMediaStatus(
                        String missionId, MediaStatus status, Pageable pageable);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select m from MediaAsset m where m.id = :id")
        Optional<MediaAsset> findByIdForUpdate(@Param("id") String id);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("""
                        select m from MediaAsset m
                        where m.mission.id = :missionId
                          and m.deviceAssignment.device.id = :deviceId
                          and m.localMediaId = :localMediaId
                        """)
        Optional<MediaAsset> findByMissionIdAndDeviceIdAndLocalMediaId(
                        String missionId, String deviceId, String localMediaId);

        /** The same file (same SHA-256) already stored for the mission and not rejected. */
        @Query("""
                        select count(m) > 0 from MediaAsset m
                        where m.mission.id = :missionId
                          and lower(m.checksumSha256) = lower(:checksum)
                          and (m.mediaStatus is null or m.mediaStatus <> com.ondemandmonitoring.media.enums.MediaStatus.REJECTED)
                        """)
        boolean existsActiveDuplicate(@Param("missionId") String missionId, @Param("checksum") String checksum);

        @Query("select m from MediaAsset m where m.mission.id = :missionId order by m.capturedAt desc")
        List<MediaAsset> findByMissionIdOrderByCapturedAtDesc(String missionId);

        @Query("select m from MediaAsset m where m.mission.id in :missionIds and m.mediaStatus = :status order by m.capturedAt desc")
        List<MediaAsset> findByMissionIdInAndMediaStatusOrderByCapturedAtDesc(
                        List<String> missionIds, MediaStatus status);

        @Query("select m from MediaAsset m where m.mission.id = :missionId and m.type = :type order by m.capturedAt desc")
        List<MediaAsset> findByMissionIdAndTypeOrderByCapturedAtDesc(String missionId, String type);

        @Query("select m from MediaAsset m where m.deviceAssignment.device.id = :deviceId order by m.capturedAt desc")
        List<MediaAsset> findByDeviceIdOrderByCapturedAtDesc(String deviceId);

        @Query("select m from MediaAsset m where m.deviceAssignment.device.id = :deviceId and m.type = :type order by m.capturedAt desc")
        List<MediaAsset> findByDeviceIdAndTypeOrderByCapturedAtDesc(String deviceId, String type);

}
