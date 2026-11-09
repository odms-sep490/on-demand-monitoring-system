package com.ondemandmonitoring.service.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.service.domain.Service;
import com.ondemandmonitoring.service.mapper.ServiceMapper;
import com.ondemandmonitoring.service.repository.ServiceRepository;
import com.ondemandmonitoring.service.service.impl.ServiceImageServiceImpl;
import com.ondemandmonitoring.service.service.impl.ServiceImageValidator;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServiceImageServiceTest {
    private final ServiceRepository repository = mock(ServiceRepository.class);
    private final IMediaObjectStorage storage = mock(IMediaObjectStorage.class);
    private final ServiceImageValidator validator = new ServiceImageValidator();
    private final ServiceImageServiceImpl images = new ServiceImageServiceImpl(
            repository, Mappers.getMapper(ServiceMapper.class), storage, validator);
    private Service service;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        service = Service.builder().name("Inspection").imageS3Bucket("bucket")
                .imageS3Key("old.png").build();
        service.setId("service-1");
        when(repository.findByIdForUpdate("service-1")).thenReturn(Optional.of(service));
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(storage.bucket()).thenReturn("bucket");
        when(storage.createPresignedGetUrl(eq("bucket"), anyString())).thenReturn("signed-url");
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void replacementKeepsOldObjectUntilCommit() {
        var response = images.upload("service-1", png());
        assertThat(response.getImageUrl()).isEqualTo("signed-url");
        assertThat(service.getImageS3Key()).startsWith("service-illustrations/service-1/");
        verify(storage, never()).deleteQuietly(any(), any());
        complete(TransactionSynchronization.STATUS_COMMITTED);
        verify(storage).deleteQuietly("bucket", "old.png");
    }

    @Test
    void rollbackRemovesNewObjectAndRetainsOldObject() {
        images.upload("service-1", png());
        String newKey = service.getImageS3Key();
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(storage).deleteQuietly("bucket", newKey);
        verify(storage, never()).deleteQuietly("bucket", "old.png");
    }

    @Test
    void failedDatabaseSaveDoesNotDeleteOldImage() {
        when(repository.saveAndFlush(any())).thenThrow(new IllegalStateException("DB unavailable"));
        assertThatThrownBy(() -> images.upload("service-1", png())).isInstanceOf(IllegalStateException.class);
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(storage, never()).deleteQuietly("bucket", "old.png");
        verify(storage).deleteQuietly(eq("bucket"), startsWith("service-illustrations/"));
    }

    @Test
    void removalClearsMetadataAndCleansAfterCommit() {
        assertThat(images.remove("service-1").getImageUrl()).isNull();
        assertThat(service.getImageS3Key()).isNull();
        assertThat(service.getImageS3Bucket()).isNull();
        verify(storage, never()).deleteQuietly(any(), any());
        complete(TransactionSynchronization.STATUS_COMMITTED);
        verify(storage).deleteQuietly("bucket", "old.png");
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
        verifyNoInteractions(storage);
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
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(storage, never()).deleteQuietly("bucket", "old.png");
        verify(repository, never()).saveAndFlush(any());
    }

    private MockMultipartFile png() {
        return new MockMultipartFile("file", "image.png", "image/png",
                new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
    }

    private void complete(int status) {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(status));
    }
}
