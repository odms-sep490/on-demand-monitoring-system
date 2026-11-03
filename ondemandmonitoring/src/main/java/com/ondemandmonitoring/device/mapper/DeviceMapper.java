package com.ondemandmonitoring.device.mapper;

import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.dto.request.DeviceCreateRequest;
import com.ondemandmonitoring.device.dto.request.DeviceUpdateRequest;
import com.ondemandmonitoring.device.dto.response.DeviceResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        uses = {DeviceModelMapper.class, DeviceImageUrlMapper.class},
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface DeviceMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "deviceModel", ignore = true)
    @Mapping(target = "imageS3Key", ignore = true)
    @Mapping(target = "imageS3Bucket", ignore = true)
    @Mapping(target = "imageUrl", ignore = true)
    Device toEntity(DeviceCreateRequest request);

    @Mapping(target = "imageUrl", source = ".", qualifiedByName = "deviceImageUrl")
    DeviceResponse toResponse(Device entity);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "deviceModel", ignore = true)
    @Mapping(target = "imageS3Key", ignore = true)
    @Mapping(target = "imageS3Bucket", ignore = true)
    @Mapping(target = "imageUrl", ignore = true)
    void updateEntityFromRequest(DeviceUpdateRequest request, @MappingTarget Device entity);
}
