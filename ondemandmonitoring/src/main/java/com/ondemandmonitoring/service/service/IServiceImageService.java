package com.ondemandmonitoring.service.service;

import com.ondemandmonitoring.service.domain.Service;
import com.ondemandmonitoring.service.dto.response.ServiceResponse;
import org.springframework.web.multipart.MultipartFile;

public interface IServiceImageService {
    ServiceResponse upload(String serviceId, MultipartFile file);
    ServiceResponse remove(String serviceId);
    String getImageUrl(Service service);
    /** Caller holds the service lock; removes the captured illustration only after DB commit. */
    void scheduleDeletion(Service service);
}
