package com.ondemandmonitoring.device.mapper;

import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import lombok.RequiredArgsConstructor;
import org.mapstruct.Named;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DeviceImageUrlMapper {

    private final IMediaObjectStorage storage;

    @Named("deviceImageUrl")
    public String imageUrl(Device device) {
        if (device == null) {
            return null;
        }
        if (device.getImageS3Key() != null && device.getImageS3Bucket() != null) {
            return storage.createPresignedGetUrl(device.getImageS3Bucket(), device.getImageS3Key());
        }
        return device.getImageUrl();
    }
}
