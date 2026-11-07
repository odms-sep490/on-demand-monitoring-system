package com.ondemandmonitoring.media.mapper;

import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.dto.response.MediaAssetResponse;
import com.ondemandmonitoring.media.dto.response.MediaResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", builder = @org.mapstruct.Builder(disableBuilder = true))
public interface MediaAssetMapper {

    @Mapping(target = "missionId", expression = "java(mediaAsset.getMissionId())")
    @Mapping(target = "deviceId", expression = "java(mediaAsset.getDeviceId())")
    MediaAssetResponse toResponse(MediaAsset mediaAsset);

    @Mapping(source = "mediaAsset.id", target = "id")
    @Mapping(target = "missionId", expression = "java(mediaAsset.getMissionId())")
    @Mapping(target = "deviceId", expression = "java(mediaAsset.getDeviceId())")
    @Mapping(source = "mediaAsset.mediaStatus", target = "status")
    @Mapping(source = "presignedUrl", target = "url")
    @Mapping(source = "expiresInSeconds", target = "expiresIn")
    @Mapping(target = "thumbnailUrl", source = "thumbnailUrl")
    @Mapping(target = "previewUrl", source = "previewUrl")
    MediaResponse toMediaResponse(MediaAsset mediaAsset, String presignedUrl, long expiresInSeconds,
                                  String thumbnailUrl, String previewUrl);
}
