package com.ondemandmonitoring.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.service.IDeviceService;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.impl.MediaAssetServiceImpl;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.MissionDeviceAssignment;
import com.ondemandmonitoring.mission.service.IMissionMediaAccessService;
import java.io.InputStream;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.web.multipart.MultipartFile;

class MediaAssetServiceTest {

    @Test
    void upload_rejectsMissingFile() {
        MediaAssetServiceImpl service = new MediaAssetServiceImpl(
                mock(IMediaObjectStorage.class),
                mock(IResultRetentionAccessService.class),
                mock(Environment.class),
                mock(IDeviceService.class),
                mock(MediaAssetRepository.class),
                mock(IMissionMediaAccessService.class), mock(com.ondemandmonitoring.mission.service.IMissionMediaEvidenceGuard.class));

        assertThatThrownBy(() -> service.upload("MISSION_001", "DEVICE-01", null, null))
                .isInstanceOf(ApiException.class)
                .hasMessage("Media file is required");
    }

    @Test
    void upload_rejectsUnsupportedContentType() {
        MediaAssetServiceImpl service = new MediaAssetServiceImpl(
                mock(IMediaObjectStorage.class),
                mock(IResultRetentionAccessService.class),
                mock(Environment.class),
                mock(IDeviceService.class),
                mock(MediaAssetRepository.class),
                mock(IMissionMediaAccessService.class), mock(com.ondemandmonitoring.mission.service.IMissionMediaEvidenceGuard.class));
        MultipartFile file = new org.springframework.mock.web.MockMultipartFile(
                "file", "capture.txt", "text/plain", "not-an-image".getBytes());

        assertThatThrownBy(() -> service.upload("MISSION_001", "DEVICE-01", null, file))
                .isInstanceOf(ApiException.class)
                .hasMessage("Only PNG, JPEG, and MP4 media are allowed");
    }

    @Test
    void upload_rejectsMismatchedVideoMediaType() {
        MediaAssetServiceImpl service = new MediaAssetServiceImpl(
                mock(IMediaObjectStorage.class),
                mock(IResultRetentionAccessService.class),
                mock(Environment.class),
                mock(IDeviceService.class),
                mock(MediaAssetRepository.class),
                mock(IMissionMediaAccessService.class), mock(com.ondemandmonitoring.mission.service.IMissionMediaEvidenceGuard.class));
        MultipartFile file = new org.springframework.mock.web.MockMultipartFile(
                "file", "clip.mp4", "video/mp4", "fake-mp4".getBytes());

        assertThatThrownBy(() -> service.upload("MISSION_001", "DEVICE-01", null, file, "IMAGE"))
                .isInstanceOf(ApiException.class)
                .hasMessage("mediaType does not match uploaded content type");
    }

    @Test
    void upload_acceptsVideoMp4AsLocalMedia() {
        Environment environment = mock(Environment.class);
        IDeviceService deviceService = mock(IDeviceService.class);
        MediaAssetRepository mediaAssetRepository = mock(MediaAssetRepository.class);
        IMissionMediaAccessService assignmentRepository = mock(IMissionMediaAccessService.class);
        Device device = new Device();
        device.setId("DEVICE-01");
        device.setDeviceCode("DEVICE-01");
        MissionDeviceAssignment assignment = assignment("MISSION_001", device);
        when(environment.getProperty("device_IMAGE_STORAGE", "local")).thenReturn("local");
        when(deviceService.getEntityById("DEVICE-01")).thenReturn(device);
        when(assignmentRepository.requireDeviceAssignment("MISSION_001", "DEVICE-01"))
                .thenReturn(assignment);
        when(mediaAssetRepository.save(any(MediaAsset.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        MediaAssetServiceImpl service = new MediaAssetServiceImpl(
                mock(IMediaObjectStorage.class),
                mock(IResultRetentionAccessService.class),
                environment,
                deviceService,
                mediaAssetRepository,
                assignmentRepository, mock(com.ondemandmonitoring.mission.service.IMissionMediaEvidenceGuard.class));
        MultipartFile file = new org.springframework.mock.web.MockMultipartFile(
                "file", "clip.mp4", "video/mp4", "fake-mp4".getBytes());

        MediaAsset saved = service.upload("MISSION_001", "DEVICE-01", null, file, "VIDEO");

        assertThat(saved.getType()).isEqualTo("VIDEO");
        assertThat(saved.getContentType()).isEqualTo("video/mp4");
        assertThat(saved.getS3Key()).contains("device-videos");
    }

    @Test
    void upload_storesImagesAndVideosInSeparateS3Folders() {
        Environment environment = mock(Environment.class);
        IDeviceService deviceService = mock(IDeviceService.class);
        MediaAssetRepository mediaAssetRepository = mock(MediaAssetRepository.class);
        IMediaObjectStorage objectStorage = mock(IMediaObjectStorage.class);
        IMissionMediaAccessService assignmentRepository = mock(IMissionMediaAccessService.class);
        Device device = new Device();
        device.setId("DEVICE-01");
        device.setDeviceCode("DEVICE-01");
        MissionDeviceAssignment assignment = assignment("MISSION_001", device);
        when(environment.getProperty("device_IMAGE_STORAGE", "local")).thenReturn("s3");
        when(deviceService.getEntityById("DEVICE-01")).thenReturn(device);
        when(assignmentRepository.requireDeviceAssignment("MISSION_001", "DEVICE-01"))
                .thenReturn(assignment);
        when(objectStorage.bucket()).thenReturn("bucket");
        when(objectStorage.put(any(), any(), eq(8L), any(InputStream.class), any()))
                .thenAnswer(invocation -> new IMediaObjectStorage.StoredObject(
                        "bucket",
                        invocation.getArgument(0),
                        "s3://bucket/" + invocation.getArgument(0)));
        when(mediaAssetRepository.save(any(MediaAsset.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        MediaAssetServiceImpl service = new MediaAssetServiceImpl(
                objectStorage,
                mock(IResultRetentionAccessService.class),
                environment,
                deviceService,
                mediaAssetRepository,
                assignmentRepository, mock(com.ondemandmonitoring.mission.service.IMissionMediaEvidenceGuard.class));
        MultipartFile imageFile = new org.springframework.mock.web.MockMultipartFile(
                "file", "capture.jpg", "image/jpeg", "fake-jpg".getBytes());
        MultipartFile videoFile = new org.springframework.mock.web.MockMultipartFile(
                "file", "clip.mp4", "video/mp4", "fake-mp4".getBytes());

        MediaAsset savedImage = service.upload("MISSION_001", "DEVICE-01", null, imageFile, "IMAGE");
        MediaAsset savedVideo = service.upload("MISSION_001", "DEVICE-01", null, videoFile, "VIDEO");

        assertThat(savedImage.getS3Key())
                .contains("/images/")
                .doesNotContain("/videos/");
        assertThat(savedVideo.getS3Key())
                .contains("/videos/")
                .doesNotContain("/images/");
    }

    private MissionDeviceAssignment assignment(String missionId, Device device) {
        Mission mission = new Mission();
        mission.setId(missionId);
        MissionDeviceAssignment assignment = new MissionDeviceAssignment();
        assignment.setMission(mission);
        assignment.setDevice(device);
        return assignment;
    }
}
