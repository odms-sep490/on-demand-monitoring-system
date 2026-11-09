package com.ondemandmonitoring.service.service.impl;

import com.ondemandmonitoring.media.validation.ImageFileValidator;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class ServiceImageValidator {
    public static final long MAX_BYTES = ImageFileValidator.MAX_BYTES;

    public String validate(MultipartFile file) {
        return ImageFileValidator.validate(file);
    }
}
