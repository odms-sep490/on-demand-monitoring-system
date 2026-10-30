package com.ondemandmonitoring.media.service;

import com.ondemandmonitoring.media.domain.MediaAsset;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public interface IMediaAssetService {

    MediaAsset upload(String missionId, String deviceId, Instant capturedAt, MultipartFile file);

    MediaAsset upload(String missionId, String deviceId, Instant capturedAt, MultipartFile file, String requestedMediaType);

    MediaAsset getById(String mediaId);

    List<MediaAsset> listByMission(String missionId, String requestedMediaType);

    MediaAsset getByDeviceAndId(String deviceId, String mediaId);

    List<MediaAsset> listByDevice(String deviceId, String requestedMediaType);

    void deleteByDeviceAndId(String deviceId, String mediaId);

    MediaContent openMedia(MediaAsset mediaAsset);

    String createPresignedGetUrl(MediaAsset mediaAsset);

    long presignedUrlExpiresSeconds();

    record MediaContent(InputStream inputStream, long contentLength, String contentType, String fileName) {}
}
