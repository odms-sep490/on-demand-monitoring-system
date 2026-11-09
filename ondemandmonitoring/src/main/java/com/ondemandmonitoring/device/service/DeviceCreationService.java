package com.ondemandmonitoring.device.service;

import com.ondemandmonitoring.device.dto.request.DeviceCreateRequest;
import com.ondemandmonitoring.device.dto.response.DeviceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Coordinates device creation and optional image storage in one database transaction. */
@Service
@RequiredArgsConstructor
public class DeviceCreationService {
    private final IDeviceService devices;
    private final IDeviceImageService images;

    @Transactional
    public DeviceResponse create(DeviceCreateRequest request, MultipartFile image) {
        DeviceResponse created = devices.create(request);
        if (image == null || image.isEmpty()) {
            return created;
        }
        return images.upload(created.getId(), image);
    }
}
