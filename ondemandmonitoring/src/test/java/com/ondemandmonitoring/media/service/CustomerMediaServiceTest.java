package com.ondemandmonitoring.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.impl.CustomerMediaServiceImpl;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.repository.MissionDeviceAssignmentRepository;
import com.ondemandmonitoring.mission.service.impl.MissionMediaAccessServiceImpl;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

class CustomerMediaServiceTest {
    @Test
    void metadataRemainsVisibleButOriginalRequiresDeliveryGate() {
        var access = mock(com.ondemandmonitoring.mission.service.IMissionMediaAccessService.class);
        var delivery = mock(com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService.class);
        var gated = new CustomerMediaServiceImpl(access, media, storage,
                org.mapstruct.factory.Mappers.getMapper(com.ondemandmonitoring.media.mapper.MediaWorkflowMapper.class),
                mock(IResultRetentionAccessService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(gated, "deliveryWorkflow", delivery);
        when(access.authorizeCustomer("m")).thenReturn("m");
        var asset = new MediaAsset(); asset.setId("asset"); asset.setMissionId("m");
        asset.setMediaStatus(MediaStatus.AVAILABLE); asset.setFileSize(12L);
        asset.setS3Bucket("test"); asset.setS3Key("original");
        when(media.findByMissionIdOrderByCapturedAtDesc("m")).thenReturn(List.of(asset));
        when(delivery.publishedMediaAssetIds(List.of("m"))).thenReturn(java.util.Set.of("asset"));
        when(delivery.originalAccessibleMissionIds(List.of("m"))).thenReturn(List.of());
        assertThat(gated.listAvailable("m")).singleElement().satisfies(response -> {
            assertThat(response.getMediaId()).isEqualTo("asset");
            assertThat(response.getDownloadUrl()).isNull();
        });
        verifyNoInteractions(storage);
        when(delivery.originalAccessibleMissionIds(List.of("m"))).thenReturn(List.of("m"));
        when(storage.createPresignedGetUrl("test", "original")).thenReturn("https://example.test/original");
        assertThat(gated.listAvailable("m").getFirst().getDownloadUrl()).isEqualTo("https://example.test/original");
    }
    private final MissionRepository missions = mock(MissionRepository.class);
    private final MediaAssetRepository media = mock(MediaAssetRepository.class);

    private final IMediaObjectStorage storage = mock(IMediaObjectStorage.class);
    private final AuthenticatedUserResolver currentUser = mock(AuthenticatedUserResolver.class);
    private final CustomerMediaServiceImpl service = new CustomerMediaServiceImpl(
            new MissionMediaAccessServiceImpl(missions, mock(MissionDeviceAssignmentRepository.class),
                    currentUser, mock(com.ondemandmonitoring.mission.service.IMissionAuthorizationService.class)), media, storage,
            org.mapstruct.factory.Mappers.getMapper(com.ondemandmonitoring.media.mapper.MediaWorkflowMapper.class),
            mock(IResultRetentionAccessService.class));

    @BeforeEach void publishedFixture() {
        var delivery = mock(com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "deliveryWorkflow", delivery);
        when(delivery.publishedMediaAssetIds(anyList())).thenReturn(java.util.Set.of("ready-1"));
        when(delivery.originalAccessibleMissionIds(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void onlyAvailableMediaGetsDownloadUrl() {
        User customer = new User();
        customer.setId(UUID.randomUUID().toString());
        when(currentUser.getCurrentUserId()).thenReturn(customer.getId());
        Order order = new Order();
        order.setCustomer(customer);
        Mission mission = new Mission();
        mission.setId("mission-1");
        mission.setOrder(order);
        when(missions.findById("mission-1")).thenReturn(Optional.of(mission));

        MediaAsset staged = new MediaAsset(); staged.setId("staged");
        staged.setMediaStatus(MediaStatus.VALIDATING);
        MediaAsset ready = new MediaAsset();
        ready.setId("ready-1");
        ready.setMediaStatus(MediaStatus.AVAILABLE);
        ready.setFileSize(10L);
        ready.setS3Bucket("bucket");
        ready.setS3Key("final/ready-1.jpg");
        when(media.findByMissionIdOrderByCapturedAtDesc("mission-1"))
                .thenReturn(List.of(staged, ready));
        when(storage.createPresignedGetUrl("bucket", "final/ready-1.jpg"))
                .thenReturn("https://s3.example/download");

        var result = service.listAvailable("mission-1");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getMediaId()).isEqualTo("ready-1");
        verify(storage, times(1)).createPresignedGetUrl("bucket", "final/ready-1.jpg");
    }

    @Test
    void libraryRetainsUndeliveredSiblingMetadataAndSignsOnlyDeliveredMission() {
        var access = mock(com.ondemandmonitoring.mission.service.IMissionMediaAccessService.class);
        var delivery = mock(com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService.class);
        var gated = new CustomerMediaServiceImpl(access, media, storage,
                org.mapstruct.factory.Mappers.getMapper(com.ondemandmonitoring.media.mapper.MediaWorkflowMapper.class),
                mock(IResultRetentionAccessService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(gated, "deliveryWorkflow", delivery);
        var ids = List.of("m1", "m2");
        when(access.ownCustomerMissionIds()).thenReturn(ids);
        when(delivery.publishedMediaAssetIds(ids)).thenReturn(java.util.Set.of("a1", "a2"));
        when(delivery.originalAccessibleMissionIds(ids)).thenReturn(List.of("m1"));
        var first = new MediaAsset(); first.setId("a1"); first.setMissionId("m1");
        first.setFileSize(10L); first.setMediaStatus(MediaStatus.AVAILABLE);
        first.setS3Bucket("test"); first.setS3Key("a1");
        var second = new MediaAsset(); second.setId("a2"); second.setMissionId("m2");
        second.setFileSize(10L); second.setMediaStatus(MediaStatus.AVAILABLE);
        second.setS3Bucket("test"); second.setS3Key("a2");
        when(media.findByMissionIdInAndMediaStatusOrderByCapturedAtDesc(ids, MediaStatus.AVAILABLE)).thenReturn(List.of(first, second));
        when(storage.createPresignedGetUrl("test", "a1")).thenReturn("https://example.test/a1");
        var responses = gated.listAllAvailable();
        assertThat(responses).extracting(response -> response.getMediaId()).containsExactly("a1", "a2");
        assertThat(responses.getFirst().getDownloadUrl()).isNotNull();
        assertThat(responses.getLast().getDownloadUrl()).isNull();
        verify(storage, never()).createPresignedGetUrl("test", "a2");
    }

    @Test
    void rejectsCustomerFromAnotherOrder() {
        User owner = new User();
        owner.setId(UUID.randomUUID().toString());
        User other = new User();
        other.setId(UUID.randomUUID().toString());
        when(currentUser.getCurrentUserId()).thenReturn(other.getId());
        Order order = new Order();
        order.setCustomer(owner);
        Mission mission = new Mission();
        mission.setOrder(order);
        when(missions.findById("mission-1")).thenReturn(Optional.of(mission));

        assertThatThrownBy(() -> service.listAvailable("mission-1"))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(storage);
    }
}
