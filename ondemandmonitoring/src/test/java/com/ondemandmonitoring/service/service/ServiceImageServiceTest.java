package com.ondemandmonitoring.service.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.media.cleanup.ObjectCleanupOutbox;
import com.ondemandmonitoring.service.domain.Service;
import com.ondemandmonitoring.service.mapper.ServiceMapper;
import com.ondemandmonitoring.service.repository.ServiceRepository;
import com.ondemandmonitoring.service.service.impl.ServiceImageServiceImpl;
import com.ondemandmonitoring.service.service.impl.ServiceImageValidator;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServiceImageServiceTest {
    private final ServiceRepository repository = mock(ServiceRepository.class);
    private final IMediaObjectStorage storage = mock(IMediaObjectStorage.class);
    private final ObjectCleanupOutbox outbox = mock(ObjectCleanupOutbox.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final ServiceImageValidator validator = new ServiceImageValidator();
    private final ServiceImageServiceImpl images = new ServiceImageServiceImpl(
            repository, Mappers.getMapper(ServiceMapper.class), storage, validator, outbox, transactionManager);
    private Service service;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(outbox.reserveUpload(any(), any(), any(), any())).thenReturn("candidate");
        service = Service.builder().name("Inspection").imageS3Bucket("bucket")
                .imageS3Key("old.png").build();
        service.setId("service-1");
        when(repository.findByIdForUpdate("service-1")).thenReturn(Optional.of(service));
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(storage.bucket()).thenReturn("bucket");
        when(storage.createPresignedGetUrl(eq("bucket"), anyString())).thenReturn("signed-url");
    }

    @Test
    void replacementReservesBeforePutAndEnqueuesOldImageInCurrentTransaction() {
        var response = images.upload("service-1", png());
        assertThat(response.getImageUrl()).isEqualTo("signed-url");
        String key = service.getImageS3Key();
        assertThat(key).startsWith("service-illustrations/service-1/");
        var ordered = inOrder(repository, outbox, storage, transactionManager);
        ordered.verify(outbox).reserveUpload("bucket", key, "SERVICE_IMAGE", "service-1");
        ordered.verify(transactionManager).getTransaction(argThat(definition ->
                definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRED));
        ordered.verify(repository).findByIdForUpdate("service-1");
        ordered.verify(outbox).enqueue("bucket", "old.png", "SERVICE_IMAGE", "service-1");
        ordered.verify(storage).put(eq(key), eq("image/png"), anyLong(), any(), eq("service-illustration"));
        ordered.verify(repository).saveAndFlush(service);
        ordered.verify(outbox).cancelReservation("candidate");
        ordered.verify(transactionManager).commit(any());
        verify(storage, never()).deleteQuietly(any(), any());
    }

    @Test
    void failedDatabaseSaveRetainsDurableUploadCandidate() {
        when(repository.saveAndFlush(any())).thenThrow(new IllegalStateException("DB unavailable"));
        assertThatThrownBy(() -> images.upload("service-1", png())).isInstanceOf(IllegalStateException.class);
        verify(outbox).reserveUpload(eq("bucket"), startsWith("service-illustrations/"), eq("SERVICE_IMAGE"), eq("service-1"));
        verify(outbox, never()).cancelReservation(any());
        verify(storage, never()).deleteQuietly(any(), any());
    }

    @Test
    void removalClearsMetadataAndEnqueuesOldImage() {
        assertThat(images.remove("service-1").getImageUrl()).isNull();
        assertThat(service.getImageS3Key()).isNull();
        assertThat(service.getImageS3Bucket()).isNull();
        verify(outbox).enqueue("bucket", "old.png", "SERVICE_IMAGE", "service-1");
        verify(storage, never()).deleteQuietly(any(), any());
    }

    @Test
    void nullOldKeyDoesNotEnqueueDeletion() {
        service.setImageS3Key(null);
        images.scheduleDeletion(service);
        images.remove("service-1");
        verifyNoInteractions(outbox, storage);
    }

    @Test
    void firstUploadOnlyReservesAndCancelsCandidate() {
        service.setImageS3Key(null);
        images.upload("service-1", png());
        verify(outbox, never()).enqueue(any(), any(), any(), any());
        verify(outbox).cancelReservation("candidate");
    }

    @Test
    void failedReservationPreventsUpload() {
        when(outbox.reserveUpload(any(), any(), any(), any())).thenThrow(new IllegalStateException("DB unavailable"));
        assertThatThrownBy(() -> images.upload("service-1", png())).isInstanceOf(IllegalStateException.class);
        verify(storage, never()).put(any(), any(), anyLong(), any(), any());
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(transactionManager);
    }

    @Test
    void serviceWithoutImageNeedsNoS3Request() {
        assertThat(images.getImageUrl(Service.builder().build())).isNull();
        verify(storage, never()).createPresignedGetUrl(any(), any());
    }

    @Test
    void externalImageIsFallbackAndS3RemainsPreferred() {
        Service externalOnly = Service.builder().imageUrl("https://images.example/service.jpg").build();
        assertThat(images.getImageUrl(externalOnly)).isEqualTo("https://images.example/service.jpg");
        assertThat(images.getImageUrl(service)).isEqualTo("signed-url");
        verify(storage).createPresignedGetUrl("bucket", "old.png");
    }

    @Test
    void invalidAndOversizedFilesAreRejectedBeforeStorage() {
        assertThatThrownBy(() -> images.upload("service-1", new MockMultipartFile(
                "file", "fake.png", "image/png", "not an image".getBytes())))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> validator.validate(new MockMultipartFile(
                "file", "large.png", "image/png", new byte[5 * 1024 * 1024 + 1])))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> validator.validate(new MockMultipartFile(
                "file", "empty.png", "image/png", new byte[0])))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void mimeMismatchIsRejected() {
        var file = png();
        assertThatThrownBy(() -> validator.validate(new MockMultipartFile(
                "file", "fake.jpg", "image/jpeg", file.getBytes())))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void unknownServiceIsRejected() {
        assertThatThrownBy(() -> images.upload("missing", png())).isInstanceOf(ApiException.class);
        verify(outbox).reserveUpload(eq("bucket"), anyString(), eq("SERVICE_IMAGE"), eq("missing"));
        verify(outbox, never()).cancelReservation(any());
        verify(storage, never()).put(any(), any(), anyLong(), any(), any());
        verify(transactionManager).rollback(any());
    }

    @Test
    void acceptsJpegAndWebpSignatures() {
        assertThat(validator.validate(new MockMultipartFile("file", "photo.jpg", "image/jpeg",
                new byte[]{(byte) 255, (byte) 216, (byte) 255}))).isEqualTo("image/jpeg");
        assertThat(validator.validate(new MockMultipartFile("file", "photo.webp", "image/webp",
                "RIFF0000WEBPVP8 ".getBytes(java.nio.charset.StandardCharsets.US_ASCII))))
                .isEqualTo("image/webp");
    }

    @Test
    void storageFailureReturnsBusinessErrorAndKeepsOldImage() {
        when(storage.put(any(), any(), anyLong(), any(), any()))
                .thenThrow(new IllegalStateException("S3 unavailable"));
        assertThatThrownBy(() -> images.upload("service-1", png())).isInstanceOf(ApiException.class);
        assertThat(service.getImageS3Key()).isEqualTo("old.png");
        verify(outbox).reserveUpload(eq("bucket"), anyString(), eq("SERVICE_IMAGE"), eq("service-1"));
        verify(outbox, never()).cancelReservation(any());
        verify(storage, never()).deleteQuietly(any(), any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void serviceDeletionEnqueuesOnlyCapturedOldImage() {
        images.scheduleDeletion(service);
        service.setImageS3Key("newer.png");
        verify(outbox).enqueue("bucket", "old.png", "SERVICE_IMAGE", "service-1");
        verifyNoMoreInteractions(outbox);
        verifyNoInteractions(storage);
    }

    @Test
    void enqueueFailurePropagatesToRollBackOwnerTransaction() {
        when(outbox.enqueue(any(), any(), any(), any())).thenThrow(new IllegalStateException("DB unavailable"));
        assertThatThrownBy(() -> images.scheduleDeletion(service)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(storage);
    }

    private MockMultipartFile png() {
        return new MockMultipartFile("file", "image.png", "image/png",
                new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
    }

}
