package com.ondemandmonitoring.stafflicense.service;

import com.ondemandmonitoring.stafflicense.dto.request.LicenseVerificationRequest;
import com.ondemandmonitoring.stafflicense.dto.request.StaffLicenseRequest;
import com.ondemandmonitoring.stafflicense.dto.response.StaffLicenseDocumentResponse;
import com.ondemandmonitoring.stafflicense.dto.response.StaffLicenseResponse;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public interface IStaffLicenseService {
    List<StaffLicenseResponse> getForUser(String userId);
    StaffLicenseResponse get(String id);
    StaffLicenseResponse create(StaffLicenseRequest request);
    StaffLicenseResponse update(String id, StaffLicenseRequest request);
    StaffLicenseResponse verify(String id, LicenseVerificationRequest request);
    StaffLicenseResponse revoke(String id, String notes);
    StaffLicenseResponse uploadDocument(String id, MultipartFile file);
    StaffLicenseDocumentResponse getDocument(String id);
}
