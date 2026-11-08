package com.ondemandmonitoring.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.MediaDerivativeStatus;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.impl.MediaDerivativeService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class MediaDerivativeServiceTest {
    private final MediaAssetRepository media = mock(MediaAssetRepository.class);
    private final IMediaObjectStorage storage = mock(IMediaObjectStorage.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final MediaDerivativeService service = new MediaDerivativeService(media, storage, transactions);
    private MediaAsset asset;

    @BeforeEach void setUp() {
        ReflectionTestUtils.setField(service, "batchSize", 8);
        ReflectionTestUtils.setField(service, "staleMinutes", 15);
        ReflectionTestUtils.setField(service, "maxAttempts", 5);
        ReflectionTestUtils.setField(service, "retryDelaySeconds", 300);
        when(transactions.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        when(media.findDerivativeCandidates(eq("IMAGE"), any(), any(), eq(5), any())).thenReturn(List.of("media"));
        asset = new MediaAsset();
        asset.setId("media");
        asset.setType("IMAGE");
        asset.setS3Bucket("bucket");
        asset.setS3Key("original.jpg");
        when(media.findByIdForUpdate("media")).thenReturn(Optional.of(asset));
        when(storage.prefix()).thenReturn("media");
    }

    @Test void generatesBoundedJpegsAndClosesOriginalStream() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2400, 1200, BufferedImage.TYPE_INT_RGB), "png", bytes);
        InputStream input = spy(new ByteArrayInputStream(bytes.toByteArray()));
        when(storage.open("bucket", "original.jpg")).thenReturn(
                new IMediaObjectStorage.StoredObjectStream(input, (long) bytes.size(), "image/png"));
        doAnswer(invocation -> {
            BufferedImage image = ImageIO.read((InputStream) invocation.getArgument(4));
            int edge = invocation.<String>getArgument(1).endsWith("thumbnail.jpg") ? 480 : 2048;
            assertThat(image.getWidth()).isEqualTo(edge);
            assertThat(image.getHeight()).isEqualTo(edge / 2);
            return null;
        }).when(storage).put(eq("bucket"), anyString(), eq("image/jpeg"), anyLong(), any(), anyString());

        assertThat(service.processBatch()).isEqualTo(1);
        assertThat(asset.getDerivativeStatus()).isEqualTo(MediaDerivativeStatus.READY);
        assertThat(asset.getThumbnailKey()).contains("attempt-1/thumbnail.jpg");
        assertThat(asset.getS3Key()).isEqualTo("original.jpg");
        verify(input, atLeastOnce()).close();
        verify(storage, times(2)).put(eq("bucket"), anyString(), eq("image/jpeg"), anyLong(), any(), anyString());
    }

    @Test void unsupportedImageDoesNotUploadDisplayCopies() {
        when(storage.open("bucket", "original.jpg")).thenReturn(new IMediaObjectStorage.StoredObjectStream(
                new ByteArrayInputStream(new byte[]{1, 2, 3}), 3L, "image/jpeg"));
        assertThat(service.processBatch()).isZero();
        assertThat(asset.getDerivativeStatus()).isEqualTo(MediaDerivativeStatus.UNSUPPORTED);
        verify(storage, never()).put(eq("bucket"), anyString(), anyString(), anyLong(), any(), anyString());
    }

    @Test void activeClaimCannotBeProcessedAgain() {
        asset.setDerivativeStatus(MediaDerivativeStatus.PROCESSING);
        asset.setDerivativeProcessedAt(Instant.now());
        assertThat(service.processBatch()).isZero();
        verify(storage, never()).open(anyString(), anyString());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = MediaDerivativeStatus.class, names = {"FAILED", "UNSUPPORTED"})
    void failedDisplayCopiesUseOriginalViewingUrl(MediaDerivativeStatus state) {
        asset.setStorageProvider("S3");
        asset.setDerivativeStatus(state);
        when(storage.createPresignedGetUrl("bucket", "original.jpg")).thenReturn("original-view");
        assertThat(service.thumbnailUrl(asset)).isEqualTo("original-view");
        assertThat(service.previewUrl(asset)).isEqualTo("original-view");
        verify(storage, times(1)).createPresignedGetUrl("bucket", "original.jpg");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = MediaDerivativeStatus.class, names = {"PENDING", "PROCESSING"})
    void pendingCopiesDoNotLoadTheOriginal(MediaDerivativeStatus state) {
        asset.setStorageProvider("S3");
        asset.setDerivativeStatus(state);
        assertThat(service.thumbnailUrl(asset)).isNull();
        assertThat(service.previewUrl(asset)).isNull();
        verify(storage, never()).createPresignedGetUrl(anyString(), anyString());
    }

    @Test void availableDerivativesTakePriorityOverOriginal() {
        asset.setStorageProvider("S3");
        asset.setDerivativeStatus(MediaDerivativeStatus.READY);
        asset.setThumbnailKey("thumb"); asset.setPreviewKey("preview");
        when(storage.createPresignedGetUrl("bucket", "thumb")).thenReturn("thumb-url");
        when(storage.createPresignedGetUrl("bucket", "preview")).thenReturn("preview-url");
        assertThat(service.thumbnailUrl(asset)).isEqualTo("thumb-url");
        assertThat(service.previewUrl(asset)).isEqualTo("preview-url");
        verify(storage, never()).createPresignedGetUrl("bucket", "original.jpg");
    }

    @Test void missingReadyThumbnailUsesPreviewAndMissingPreviewUsesOriginal() {
        asset.setStorageProvider("S3");
        asset.setDerivativeStatus(MediaDerivativeStatus.READY);
        asset.setPreviewKey("preview");
        when(storage.createPresignedGetUrl("bucket", "preview")).thenReturn("preview-url");
        when(storage.createPresignedGetUrl("bucket", "original.jpg")).thenReturn("original-view");
        assertThat(service.thumbnailUrl(asset)).isEqualTo("preview-url");
        asset.setPreviewKey(null);
        assertThat(service.thumbnailUrl(asset)).isEqualTo("original-view");
        assertThat(service.previewUrl(asset)).isEqualTo("original-view");
    }

    @Test void videoOriginalIsPlayableButNeverUsedAsAPoster() {
        asset.setStorageProvider("S3"); asset.setType("VIDEO");
        asset.setDerivativeStatus(MediaDerivativeStatus.FAILED);
        when(storage.createPresignedGetUrl("bucket", "original.jpg")).thenReturn("original-video");
        assertThat(service.thumbnailUrl(asset)).isNull();
        assertThat(service.previewUrl(asset)).isEqualTo("original-video");
    }

    @Test void retryDoesNotHideAnAlreadyAvailableOriginalFallback() {
        asset.setStorageProvider("S3"); asset.setDerivativeStatus(MediaDerivativeStatus.PROCESSING);
        asset.setDerivativeAttemptCount(2);
        when(storage.createPresignedGetUrl("bucket", "original.jpg")).thenReturn("original-view");
        assertThat(service.previewUrl(asset)).isEqualTo("original-view");
        assertThat(service.thumbnailUrl(asset)).isEqualTo("original-view");
    }

    @Test void deletedMediaHasNoOriginalFallback() {
        asset.setStorageProvider("S3"); asset.setDerivativeStatus(MediaDerivativeStatus.FAILED);
        asset.setStorageStatus(com.ondemandmonitoring.media.enums.MediaStorageStatus.DELETED);
        assertThat(service.thumbnailUrl(asset)).isNull();
        assertThat(service.previewUrl(asset)).isNull();
        verify(storage, never()).createPresignedGetUrl(anyString(), anyString());
    }

    @Test void oldWorkerCannotOverwriteOrDeleteANewerAttemptsFiles() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "png", bytes);
        when(storage.open("bucket", "original.jpg")).thenReturn(new IMediaObjectStorage.StoredObjectStream(
                new ByteArrayInputStream(bytes.toByteArray()), (long) bytes.size(), "image/png"));
        doAnswer(invocation -> {
            asset.setDerivativeAttemptCount(2);
            asset.setDerivativeStatus(MediaDerivativeStatus.READY);
            asset.setThumbnailKey("new-thumbnail");
            return null;
        }).when(storage).put(eq("bucket"), anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(service.processBatch()).isZero();
        assertThat(asset.getThumbnailKey()).isEqualTo("new-thumbnail");
        verify(storage).deleteQuietly("bucket", "media/derived/media/v1/attempt-1/thumbnail.jpg");
        verify(storage, never()).deleteQuietly("bucket", "new-thumbnail");
    }
}
