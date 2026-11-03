package com.ondemandmonitoring.device.service;

import com.ondemandmonitoring.device.dto.response.DeviceResponse;
import org.springframework.web.multipart.MultipartFile;

public interface IDeviceImageService {
    DeviceResponse upload(String deviceId, MultipartFile file);

    DeviceResponse remove(String deviceId);
}
