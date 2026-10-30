package com.ondemandmonitoring.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.domain.MediaNotificationOutbox;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.domain.MediaUploadAttempt;
import com.ondemandmonitoring.media.domain.StorageEventInbox;
import com.ondemandmonitoring.media.enums.UploadAttemptStatus;
import com.ondemandmonitoring.media.repository.ManualUploadTaskRepository;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.repository.MediaNotificationOutboxRepository;
import com.ondemandmonitoring.media.repository.MediaUploadAttemptRepository;
import com.ondemandmonitoring.media.repository.StorageEventInboxRepository;
import com.ondemandmonitoring.media.service.impl.MediaValidationServiceImpl;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MediaValidationServiceImplTest {

    @Mock MediaUploadAttemptRepository attempts;
    @Mock MediaAssetRepository media;
    @Mock StorageEventInboxRepository inbox;
    @Mock ManualUploadTaskRepository manualTasks;
    @Mock IMediaAuditService auditService;
    @org.mockito.Spy com.ondemandmonitoring.media.policy.MediaUploadPolicy policy;
    @Mock MediaNotificationOutboxRepository outbox;
    @Mock IMediaObjectStorage storage;
    MediaValidationServiceImpl service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new MediaValidationServiceImpl(attempts, media, inbox, manualTasks,
                new com.ondemandmonitoring.media.service.impl.ManualUploadTaskServiceImpl(manualTasks),
                auditService, policy, storage,
                new com.ondemandmonitoring.media.service.impl.MediaObjectVerificationServiceImpl(storage), org.mockito.Mockito.mock(com.ondemandmonitoring.mission.service.IMissionMediaEvidenceGuard.class));
    }

    @Test
    void failedManualValidationKeepsManualTaskAndLocksAggregateFirst() {
        var asset = new MediaAsset();
        asset.setId("media-manual");
        asset.setMissionId("mission-1");
        asset.setS3Bucket("media-bucket");
        asset.setFileSize(10L);
        var attempt = new MediaUploadAttempt();
        attempt.setId("manual-attempt");
        attempt.setMedia(asset);
        attempt.setManualAttempt(true);
        attempt.setStatus(UploadAttemptStatus.UPLOADED);
        String key = "drone-media/staging/manual.jpg";
        when(storage.bucket()).thenReturn("media-bucket");
        when(attempts.findMediaIdByStorageKey(key)).thenReturn(Optional.of(asset.getId()));
        when(media.findByIdForUpdate(asset.getId())).thenReturn(Optional.of(asset));
        when(attempts.findByStorageKeyForUpdate(key)).thenReturn(Optional.of(attempt));
        when(attempts.findFirstByMediaIdOrderByAttemptNumberDesc(asset.getId())).thenReturn(Optional.of(attempt));
        when(storage.inspect("media-bucket", key)).thenReturn(
                new IMediaObjectStorage.StoredObjectInfo(9L, "image/jpeg", Map.of()));
        service.processObjectCreated("media-bucket", key, "manual-event");
        assertThat(asset.getMediaStatus()).isEqualTo(MediaStatus.MANUAL_UPLOAD_REQUIRED);
        assertThat(attempt.getStatus()).isEqualTo(UploadAttemptStatus.FAILED);
        verify(manualTasks).save(any(com.ondemandmonitoring.media.domain.ManualUploadTask.class));
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(media, attempts);
        order.verify(media).findByIdForUpdate(asset.getId());
        order.verify(attempts).findByStorageKeyForUpdate(key);
        org.mockito.Mockito.verify(storage, org.mockito.Mockito.never())
                .copy(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test void lateObjectCreatedAfterCleanupIsDeletedWithoutPublishingOrValidating() {
        var asset=new MediaAsset();asset.setId("removed");asset.setStorageStatus(com.ondemandmonitoring.media.enums.MediaStorageStatus.DELETED);
        var attempt=new MediaUploadAttempt();attempt.setId("old-attempt");attempt.setMedia(asset);attempt.setStatus(UploadAttemptStatus.UPLOADED);
        when(storage.bucket()).thenReturn("bucket");when(attempts.findMediaIdByStorageKey("staging/late.jpg")).thenReturn(Optional.of("removed"));
        when(media.findByIdForUpdate("removed")).thenReturn(Optional.of(asset));when(attempts.findByStorageKeyForUpdate("staging/late.jpg")).thenReturn(Optional.of(attempt));
        service.processObjectCreated("bucket","staging/late.jpg","late-event");
        verify(storage).delete("bucket","staging/late.jpg");
        verify(storage,never()).copy(any(),any(),any());verify(media,never()).save(any());
        verify(inbox).save(any(StorageEventInbox.class));
        org.mockito.Mockito.verifyNoInteractions(outbox);
    }

    @Test
    void successfulValidationPersistsMissionAndBucketForLegacySchema() throws Exception {
        String bucket = "media-bucket";
        String stagingKey = "drone-media/staging/missions/mission-1/drones/DRN-1/media-1/1.jpg";
        byte[] image = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));

        MediaAsset captured = new MediaAsset();
        captured.setId("media-1");
        captured.setMissionId("mission-1");
        captured.setS3Bucket(bucket);
        captured.setFileSize((long) image.length);
        captured.setContentType("image/jpeg");
        captured.setChecksumSha256(checksum);

        MediaUploadAttempt attempt = new MediaUploadAttempt();
        attempt.setId("attempt-1");
        attempt.setMedia(captured);
        attempt.setStatus(UploadAttemptStatus.UPLOADED);

        when(storage.bucket()).thenReturn(bucket);
        when(attempts.findMediaIdByStorageKey(stagingKey)).thenReturn(Optional.of(captured.getId()));
        when(media.findByIdForUpdate(captured.getId())).thenReturn(Optional.of(captured));
        when(attempts.findByStorageKeyForUpdate(stagingKey)).thenReturn(Optional.of(attempt));
        when(attempts.findFirstByMediaIdOrderByAttemptNumberDesc("media-1"))
                .thenReturn(Optional.of(attempt));
        when(storage.inspect(bucket, stagingKey)).thenReturn(
                new IMediaObjectStorage.StoredObjectInfo(
                        (long) image.length, "image/jpeg",
                        Map.of("media-id", "media-1", "sha256", checksum)));
        when(storage.open(bucket, stagingKey)).thenReturn(
                new IMediaObjectStorage.StoredObjectStream(
                        new ByteArrayInputStream(image), (long) image.length, "image/jpeg"));

        service.processObjectCreated(bucket, stagingKey, "event-1");

        // No per-file manager approval any more: the media is available right after technical validation.
        assertThat(captured.getMediaStatus()).isEqualTo(com.ondemandmonitoring.media.enums.MediaStatus.AVAILABLE);
        assertThat(captured.getAvailableAt()).isNotNull();
        verify(outbox, never()).save(any());

        ArgumentCaptor<StorageEventInbox> event = ArgumentCaptor.forClass(StorageEventInbox.class);
        verify(inbox).save(event.capture());
        assertThat(event.getValue().getBucket()).isEqualTo(bucket);
        assertThat(attempt.getStatus()).isEqualTo(UploadAttemptStatus.SUCCEEDED);
        verify(storage).copy(bucket, stagingKey, stagingKey.replace("/staging/", "/final/"));
    }
}
