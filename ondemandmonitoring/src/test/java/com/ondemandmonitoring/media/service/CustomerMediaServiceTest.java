package com.ondemandmonitoring.media.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.mapper.MediaWorkflowMapper;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.impl.*;
import com.ondemandmonitoring.mission.service.IMissionMediaAccessService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

class CustomerMediaServiceTest {
    final IMissionMediaAccessService access = mock(IMissionMediaAccessService.class);
    final MediaAssetRepository media = mock(MediaAssetRepository.class);
    final IMediaObjectStorage storage = mock(IMediaObjectStorage.class);
    final MediaDerivativeService derivatives = mock(MediaDerivativeService.class);
    final IResultRetentionAccessService retention = mock(IResultRetentionAccessService.class);
    final IDeliveryWorkflowService delivery = mock(IDeliveryWorkflowService.class);
    final CustomerMediaServiceImpl service = new CustomerMediaServiceImpl(access, media, storage, derivatives,
            org.mapstruct.factory.Mappers.getMapper(MediaWorkflowMapper.class), retention);
    MediaAsset asset;
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "deliveryWorkflow", delivery);
        asset = new MediaAsset(); asset.setId("a"); asset.setMissionId("m"); asset.setFileSize(12L);
        asset.setMediaStatus(MediaStatus.AVAILABLE); asset.setS3Bucket("bucket"); asset.setS3Key("original");
        asset.setOriginalFileName("image.jpg"); asset.setStorageProvider("S3");
        when(access.authorizeCustomer("m")).thenReturn("m");
        when(media.findById("a")).thenReturn(Optional.of(asset));
        when(media.findOrderIdByMediaId("a")).thenReturn(Optional.of("order"));
        when(media.findByMissionIdOrderByCapturedAtDesc("m")).thenReturn(List.of(asset));
        when(delivery.publishedMediaAssetIds(List.of("m"))).thenReturn(Set.of("a"));
        when(derivatives.thumbnailUrl(asset)).thenReturn("thumbnail");
        when(derivatives.previewUrl(asset)).thenReturn("preview");
    }
    @Test void metadataNeverIssuesOriginalUrlEvenWhenEligible() {
        when(delivery.originalAccessibleMissionIds(List.of("m"))).thenReturn(List.of("m"));
        var result = service.listAvailable("m").getFirst();
        assertThat(result.getDownloadUrl()).isNull();
        assertThat(result.isCanDownloadOriginal()).isTrue();
        assertThat(result.getThumbnailUrl()).isEqualTo("thumbnail");
        assertThat(result.getPreviewUrl()).isEqualTo("preview");
        verifyNoInteractions(storage);
    }
    @Test void explicitDownloadChecksGateAndRetentionBeforeSigning() {
        when(storage.createPresignedDownloadUrl("bucket", "original", "image.jpg")).thenReturn("signed");
        when(storage.presignedUrlExpiresSeconds()).thenReturn(600L);
        var result = service.createOriginalDownload("a");
        assertThat(result.downloadUrl()).isEqualTo("signed");
        var ordered = inOrder(delivery, retention, storage);
        ordered.verify(delivery).requireOriginalAccessForMedia("order", "a");
        ordered.verify(retention).requireAccessible(asset);
        ordered.verify(storage).createPresignedDownloadUrl("bucket", "original", "image.jpg");
    }
    @Test void unpaidOrUnacceptedDeliveryCannotIssueOriginal() {
        doThrow(new ApiException(ErrorCode.INVOICE_NOT_PAID)).when(delivery).requireOriginalAccessForMedia("order", "a");
        assertThatThrownBy(() -> service.createOriginalDownload("a")).isInstanceOf(ApiException.class);
        verifyNoInteractions(storage);
    }
    @Test void expiredMediaCannotIssueOriginal() {
        doThrow(new ApiException(ErrorCode.RESULT_RETENTION_EXPIRED)).when(retention).requireAccessible(asset);
        assertThatThrownBy(() -> service.createOriginalDownload("a")).isInstanceOf(ApiException.class);
        verifyNoInteractions(storage);
    }
    @Test void reserveMediaCannotIssueOriginal() {
        when(delivery.publishedMediaAssetIds(List.of("m"))).thenReturn(Set.of());
        assertThatThrownBy(() -> service.createOriginalDownload("a")).isInstanceOf(ApiException.class);
        verify(delivery, never()).requireOriginalAccessForMedia(anyString(), anyString());
        verifyNoInteractions(storage);
    }
    @Test void OtherCustomersCannotIssueOriginal() {
        doThrow(new ApiException(ErrorCode.ACCESS_DENIED)).when(access).authorizeCustomer("m");
        assertThatThrownBy(() -> service.createOriginalDownload("a")).isInstanceOf(ApiException.class);
        verifyNoInteractions(storage);
    }

    private CustomerMediaServiceImpl fallbackService() {
        var display = new MediaDerivativeService(media, storage,
                mock(org.springframework.transaction.PlatformTransactionManager.class));
        var result = new CustomerMediaServiceImpl(access, media, storage, display,
                org.mapstruct.factory.Mappers.getMapper(MediaWorkflowMapper.class), retention);
        ReflectionTestUtils.setField(result, "deliveryWorkflow", delivery);
        asset.setType("IMAGE"); asset.setStorageProvider("S3");
        asset.setDerivativeStatus(com.ondemandmonitoring.media.enums.MediaDerivativeStatus.FAILED);
        return result;
    }

    @Test void publishedFailedImageDoesNotExposeOriginalThroughDisplayFields() {
        var result = fallbackService().getAvailable("a");
        assertThat(result.getThumbnailUrl()).isNull();
        assertThat(result.getPreviewUrl()).isNull();
        assertThat(result.getDownloadUrl()).isNull();
        assertThat(result.isCanDownloadOriginal()).isFalse();
        verifyNoInteractions(storage);
    }

    @Test void unpublishedFallbackCannotBeSigned() {
        var fallback = fallbackService();
        when(delivery.publishedMediaAssetIds(List.of("m"))).thenReturn(Set.of());
        assertThatThrownBy(() -> fallback.getAvailable("a")).isInstanceOf(ApiException.class);
        verifyNoInteractions(storage);
    }

    @Test void expiredFallbackCannotBeSignedBeforeAcceptance() {
        var fallback = fallbackService();
        doThrow(new ApiException(ErrorCode.RESULT_RETENTION_EXPIRED)).when(retention).requireAccessible(asset);
        assertThatThrownBy(() -> fallback.getAvailable("a")).isInstanceOf(ApiException.class);
        verifyNoInteractions(storage);
    }

    @Test void anotherCustomerCannotViewFallback() {
        var fallback = fallbackService();
        doThrow(new ApiException(ErrorCode.ACCESS_DENIED)).when(access).authorizeCustomer("m");
        assertThatThrownBy(() -> fallback.getAvailable("a")).isInstanceOf(ApiException.class);
        verifyNoInteractions(storage);
    }

    @Test void failedPreviewRefreshNeverFallsBackToOriginalEvenWhenDownloadCouldBeAuthorized() {
        asset.setStorageProvider("S3");
        asset.setDerivativeStatus(com.ondemandmonitoring.media.enums.MediaDerivativeStatus.FAILED);
        assertThatThrownBy(() -> service.createViewingFallback("a"))
                .isInstanceOf(ApiException.class).hasMessageContaining("Bản xem trước chưa khả dụng");
        verifyNoInteractions(storage);
        verify(delivery, never()).requireOriginalAccessForMedia(anyString(), anyString());
    }

    @Test void viewingRefreshUsesReadyDerivativeInsteadOfExposingOriginal() {
        asset.setType("IMAGE");
        asset.setDerivativeStatus(com.ondemandmonitoring.media.enums.MediaDerivativeStatus.READY);
        asset.setPreviewKey("derived/preview.jpg");
        asset.setThumbnailKey("derived/thumbnail.jpg");
        when(derivatives.viewingDerivativeKeys(asset)).thenReturn(List.of("derived/preview.jpg", "derived/thumbnail.jpg"));
        when(storage.inspect("bucket", "derived/preview.jpg")).thenReturn(
                new IMediaObjectStorage.StoredObjectInfo(32_000L, "image/jpeg", Map.of()));
        when(storage.createPresignedGetUrl("bucket", "derived/preview.jpg")).thenReturn("fresh-preview");
        when(storage.presignedUrlExpiresSeconds()).thenReturn(600L);

        var result = service.createViewingFallback("a");

        assertThat(result.viewUrl()).isEqualTo("fresh-preview");
        assertThat(result.fileSize()).isEqualTo(32_000L);
        verify(storage).inspect("bucket", "derived/preview.jpg");
        verify(storage, never()).inspect("bucket", "original");
        verify(storage, never()).createPresignedGetUrl("bucket", "original");
    }

    @Test void viewingRefreshUsesThumbnailWhenReadyPreviewObjectIsMissing() {
        asset.setType("IMAGE");
        asset.setDerivativeStatus(com.ondemandmonitoring.media.enums.MediaDerivativeStatus.READY);
        asset.setPreviewKey("derived/missing-preview.jpg");
        asset.setThumbnailKey("derived/thumbnail.jpg");
        when(derivatives.viewingDerivativeKeys(asset)).thenReturn(List.of("derived/missing-preview.jpg", "derived/thumbnail.jpg"));
        when(storage.inspect("bucket", "derived/missing-preview.jpg")).thenThrow(
                software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(404).build());
        when(storage.inspect("bucket", "derived/thumbnail.jpg")).thenReturn(
                new IMediaObjectStorage.StoredObjectInfo(16_000L, "image/jpeg", Map.of()));
        when(storage.createPresignedGetUrl("bucket", "derived/thumbnail.jpg")).thenReturn("fresh-thumbnail");

        var result = service.createViewingFallback("a");

        assertThat(result.viewUrl()).isEqualTo("fresh-thumbnail");
        assertThat(result.fileSize()).isEqualTo(16_000L);
        verify(storage, never()).inspect("bucket", "original");
        verify(storage, never()).createPresignedGetUrl("bucket", "original");
    }

    @Test void missingReadyDerivativeCannotExposeOriginal() {
        asset.setDerivativeStatus(com.ondemandmonitoring.media.enums.MediaDerivativeStatus.READY);
        when(derivatives.viewingDerivativeKeys(asset)).thenReturn(List.of("derived/missing-preview.jpg"));
        when(storage.inspect("bucket", "derived/missing-preview.jpg")).thenThrow(
                software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(404).build());
        assertThatThrownBy(() -> service.createViewingFallback("a"))
                .isInstanceOf(ApiException.class).hasMessageContaining("Bản xem trước chưa khả dụng");
        verify(storage, never()).inspect("bucket", "original");
        verify(storage, never()).createPresignedGetUrl(anyString(), anyString());
    }

    @Test void viewingRefreshDoesNotExposeOriginalWhileFirstDerivativeAttemptIsPending() {
        asset.setType("IMAGE");
        asset.setDerivativeStatus(com.ondemandmonitoring.media.enums.MediaDerivativeStatus.PENDING);

        assertThatThrownBy(() -> service.createViewingFallback("a"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Bản xem trước đang được xử lý");
        verifyNoInteractions(storage);
    }

    @Test void viewingRefreshDoesNotExposeOriginalDuringFirstDerivativeAttempt() {
        asset.setType("VIDEO");
        asset.setDerivativeStatus(com.ondemandmonitoring.media.enums.MediaDerivativeStatus.PROCESSING);
        asset.setDerivativeAttemptCount(1);

        assertThatThrownBy(() -> service.createViewingFallback("a"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Bản xem trước đang được xử lý");
        verifyNoInteractions(storage);
    }

    @Test void viewingRefreshUsesVideoPreviewAndNeverItsPoster() {
        asset.setType("VIDEO");
        asset.setDerivativeStatus(com.ondemandmonitoring.media.enums.MediaDerivativeStatus.READY);
        asset.setPreviewKey("derived/preview.mp4");
        asset.setThumbnailKey("derived/poster.jpg");
        when(derivatives.viewingDerivativeKeys(asset)).thenReturn(List.of("derived/preview.mp4"));
        when(storage.inspect("bucket", "derived/preview.mp4")).thenReturn(
                new IMediaObjectStorage.StoredObjectInfo(88_000L, "video/mp4", Map.of()));
        when(storage.createPresignedGetUrl("bucket", "derived/preview.mp4")).thenReturn("fresh-video-preview");

        var result = service.createViewingFallback("a");

        assertThat(result.viewUrl()).isEqualTo("fresh-video-preview");
        verify(storage, never()).inspect("bucket", "derived/poster.jpg");
        verify(storage, never()).createPresignedGetUrl("bucket", "original");
    }

    @Test void missingS3OriginalDoesNotIssueAnotherBrokenUrl() {
        asset.setStorageProvider("S3");
        when(storage.inspect("bucket", "original")).thenThrow(
                software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(404).build());
        assertThatThrownBy(() -> service.createViewingFallback("a")).isInstanceOf(ApiException.class);
        verify(storage, never()).createPresignedGetUrl(anyString(), anyString());
        assertThatThrownBy(() -> service.createOriginalDownload("a")).isInstanceOf(ApiException.class);
        verify(storage, never()).createPresignedDownloadUrl(anyString(), anyString(), anyString());
    }

    @Test void legacyMissingSizeDoesNotUnlockOriginalRecovery() {
        asset.setStorageProvider("S3"); asset.setFileSize(null);
        asset.setDerivativeStatus(com.ondemandmonitoring.media.enums.MediaDerivativeStatus.FAILED);
        assertThatThrownBy(() -> service.createViewingFallback("a")).isInstanceOf(ApiException.class);
        verifyNoInteractions(storage);
    }

    @Test void transientDerivativeStorageFailureDoesNotExposeOriginal() {
        asset.setDerivativeStatus(com.ondemandmonitoring.media.enums.MediaDerivativeStatus.READY);
        when(derivatives.viewingDerivativeKeys(asset)).thenReturn(List.of("derived/preview.jpg"));
        when(storage.inspect("bucket", "derived/preview.jpg")).thenThrow(
                software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(503).build());
        assertThatThrownBy(() -> service.createViewingFallback("a"))
                .isInstanceOf(software.amazon.awssdk.services.s3.model.S3Exception.class);
        verify(storage, never()).inspect("bucket", "original");
        verify(storage, never()).createPresignedGetUrl(anyString(), anyString());
    }

    @Test void refreshStillRejectsReserveAndOtherCustomers() {
        asset.setStorageProvider("S3");
        when(delivery.publishedMediaAssetIds(List.of("m"))).thenReturn(Set.of());
        assertThatThrownBy(() -> service.createViewingFallback("a")).isInstanceOf(ApiException.class);
        verifyNoInteractions(storage);
        doThrow(new ApiException(ErrorCode.ACCESS_DENIED)).when(access).authorizeCustomer("m");
        assertThatThrownBy(() -> service.createViewingFallback("a")).isInstanceOf(ApiException.class);
        verifyNoInteractions(storage);
    }

    @Test void expiredViewingRefreshIsRejectedBeforeS3Inspection() {
        asset.setStorageProvider("S3");
        doThrow(new ApiException(ErrorCode.RESULT_RETENTION_EXPIRED)).when(retention).requireAccessible(asset);
        assertThatThrownBy(() -> service.createViewingFallback("a")).isInstanceOf(ApiException.class);
        verifyNoInteractions(storage);
    }
}
