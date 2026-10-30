package com.ondemandmonitoring.media.repository;

import com.ondemandmonitoring.media.domain.MediaUploadAttempt;
import java.util.Optional;
import java.util.List;
import com.ondemandmonitoring.media.enums.UploadAttemptStatus;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaUploadAttemptRepository extends JpaRepository<MediaUploadAttempt, String> {
    List<MediaUploadAttempt> findByMediaIdOrderByAttemptNumberDesc(String mediaId);

    Optional<MediaUploadAttempt> findByIdAndMediaId(String id, String mediaId);

    Optional<MediaUploadAttempt> findFirstByMediaIdOrderByAttemptNumberDesc(String mediaId);

    @Query("select a.media.id from MediaUploadAttempt a where a.storageKey = :key")
    Optional<String> findMediaIdByStorageKey(@Param("key") String key);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from MediaUploadAttempt a where a.storageKey = :key")
    Optional<MediaUploadAttempt> findByStorageKeyForUpdate(@Param("key") String key);

    /**
     * Counts automatic attempts that actually FAILED a transfer or failed object validation.
     * Prepares, abandoned presigned URLs and retries that were never transferred stay PENDING
     * and are therefore never counted as failed uploads.
     */
    @Query("select count(a) from MediaUploadAttempt a where a.media.id = :mediaId and a.manualAttempt = false and a.status = :status and (a.failureCode is null or a.failureCode <> 'ORPHAN_CLEANUP')")
    long countByMediaIdAndManualAttemptFalseAndStatus(@Param("mediaId") String mediaId, @Param("status") UploadAttemptStatus status);

    List<MediaUploadAttempt> findByStatusOrderByUpdatedAtAsc(
            UploadAttemptStatus status, Pageable pageable);
}
