package com.ondemandmonitoring.stafflicense.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.stafflicense.domain.StaffLicense;
import com.ondemandmonitoring.stafflicense.dto.request.LicenseVerificationRequest;
import com.ondemandmonitoring.stafflicense.dto.request.StaffLicenseRequest;
import com.ondemandmonitoring.stafflicense.dto.response.StaffLicenseDocumentResponse;
import com.ondemandmonitoring.stafflicense.dto.response.StaffLicenseResponse;
import com.ondemandmonitoring.stafflicense.enumeration.LicenseStatus;
import com.ondemandmonitoring.stafflicense.enumeration.VerificationStatus;
import com.ondemandmonitoring.stafflicense.repository.StaffLicenseRepository;
import com.ondemandmonitoring.stafflicense.service.IStaffLicenseService;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.repository.UserRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Slf4j
public class StaffLicenseServiceImpl implements IStaffLicenseService {
    private static final long MAX_DOCUMENT_BYTES = 10L * 1024 * 1024;
    private static final String DEVELOPMENT_SEED_ISSUER =
            "ODMS Development Seed Registry — Not valid for aviation operations";
    private static final String LEGACY_ISSUER = "ODMS Demo Training Center — Not an Official Issuer";

    private final StaffLicenseRepository licenses;
    private final UserRepository users;
    private final AuthenticatedUserResolver currentUser;
    private final IMediaObjectStorage storage;

    @Override
    @Transactional(readOnly = true)
    public java.util.List<StaffLicenseResponse> getForUser(String userId) {
        User viewer = currentUser.getCurrentUser();
        User target = user(userId);
        requireStaff(target);
        requireReadAccess(viewer, target);
        return licenses.findAllByUser_IdOrderByLicenseTypeAscIssuedDateDesc(userId).stream()
                .map(this::response).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public StaffLicenseResponse get(String id) {
        StaffLicense license = license(id);
        requireReadAccess(currentUser.getCurrentUser(), license.getUser());
        return response(license);
    }

    @Override
    @Transactional
    public StaffLicenseResponse create(StaffLicenseRequest request) {
        User actor = currentUser.getCurrentUser();
        User owner = user(request.userId());
        requireStaff(owner);
        requireManageAccess(actor, owner);
        validateDates(request);
        validateStatus(request);
        if (licenses.existsByUser_IdAndLicenseNumberIgnoreCase(owner.getId(), request.licenseNumber().trim())) {
            throw new ApiException(ErrorCode.RESOURCE_ALREADY_EXISTS, "License number already exists for this staff member");
        }
        StaffLicense license = new StaffLicense();
        license.setUser(owner);
        apply(license, request);
        license.setVerificationStatus(VerificationStatus.PENDING);
        return response(licenses.save(license));
    }

    @Override
    @Transactional
    public StaffLicenseResponse update(String id, StaffLicenseRequest request) {
        StaffLicense license = license(id);
        User actor = currentUser.getCurrentUser();
        User target = user(request.userId());
        requireStaff(target);
        requireManageAccess(actor, license.getUser());
        requireManageAccess(actor, target);
        if (license.getLicenseStatus() == LicenseStatus.REVOKED) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "A revoked license cannot be edited");
        }
        if (!license.getUser().getId().equals(target.getId())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "A license cannot be transferred to another staff member");
        }
        validateDates(request);
        validateStatus(request);
        if (!license.getLicenseNumber().equalsIgnoreCase(request.licenseNumber().trim())
                && licenses.existsByUser_IdAndLicenseNumberIgnoreCase(target.getId(), request.licenseNumber().trim())) {
            throw new ApiException(ErrorCode.RESOURCE_ALREADY_EXISTS, "License number already exists for this staff member");
        }
        apply(license, request);
        license.setVerificationStatus(VerificationStatus.PENDING);
        license.setVerifiedBy(null);
        license.setVerifiedAt(null);
        return response(licenses.save(license));
    }

    @Override
    @Transactional
    public StaffLicenseResponse verify(String id, LicenseVerificationRequest request) {
        StaffLicense license = license(id);
        requireAdminOrManager(currentUser.getCurrentUser());
        if (license.getDocumentStoragePath() == null || license.getDocumentStoragePath().isBlank()
                || isNonOperationalSeed(license)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "A real license document must be attached before verification");
        }
        if (request.status() == VerificationStatus.PENDING) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Verification must be VERIFIED or REJECTED");
        }
        license.setVerificationStatus(request.status());
        if (request.notes() != null) license.setNotes(blankToNull(request.notes()));
        license.setVerifiedBy(currentUser.getCurrentUser());
        license.setVerifiedAt(java.time.LocalDateTime.now());
        return response(licenses.save(license));
    }

    @Override
    @Transactional
    public StaffLicenseResponse revoke(String id, String notes) {
        StaffLicense license = license(id);
        requireAdminOrManager(currentUser.getCurrentUser());
        license.setLicenseStatus(LicenseStatus.REVOKED);
        if (notes != null) license.setNotes(blankToNull(notes));
        return response(licenses.save(license));
    }

    @Override
    @Transactional
    public StaffLicenseResponse uploadDocument(String id, MultipartFile file) {
        StaffLicense license = license(id);
        User actor = currentUser.getCurrentUser();
        requireManageAccess(actor, license.getUser());
        String contentType = validateDocument(file);
        String extension = contentType.equals("application/pdf") ? ".pdf"
                : contentType.equals("image/png") ? ".png" : ".jpg";
        String key = "staff-licenses/" + license.getUser().getId() + "/" + license.getId()
                + "/" + UUID.randomUUID() + extension;
        String bucket = storage.bucket();
        String oldKey = license.getDocumentStoragePath();
        String oldBucket = bucket;
        try (var input = file.getInputStream()) {
            storage.put(key, contentType, file.getSize(), input, "staff-license-document");
        } catch (IOException | RuntimeException failure) {
            log.warn("Staff license document upload failed. licenseId={}", id, failure);
            throw new ApiException(ErrorCode.MEDIA_UPLOAD_FAILED, "Unable to store license document");
        }
        scheduleCleanup(oldBucket, oldKey, bucket, key);
        license.setDocumentStoragePath(key);
        license.setDocumentFileName(safeFileName(file.getOriginalFilename()));
        license.setVerificationStatus(VerificationStatus.PENDING);
        license.setVerifiedBy(null);
        license.setVerifiedAt(null);
        return response(licenses.save(license));
    }

    @Override
    @Transactional(readOnly = true)
    public StaffLicenseDocumentResponse getDocument(String id) {
        StaffLicense license = license(id);
        requireReadAccess(currentUser.getCurrentUser(), license.getUser());
        if (license.getDocumentStoragePath() == null || license.getDocumentStoragePath().isBlank()) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No document is attached to this license");
        }
        return new StaffLicenseDocumentResponse(
                storage.createPresignedGetUrl(storage.bucket(), license.getDocumentStoragePath()),
                storage.presignedUrlExpiresSeconds(), license.getDocumentFileName());
    }

    private StaffLicense license(String id) {
        return licenses.findWithUserById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Staff license not found"));
    }

    private User user(String id) {
        return users.findById(id).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
    }

    private void requireStaff(User user) {
        if (user.getRole().getCode() != RoleCode.STAFF) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Staff licenses can only belong to a staff user");
        }
    }

    private void requireReadAccess(User actor, User owner) {
        if (isManager(actor) || actor.getId().equals(owner.getId())) return;
        throw new ApiException(ErrorCode.ACCESS_DENIED);
    }

    private void requireManageAccess(User actor, User owner) {
        if (isManager(actor) || (actor.getRole().getCode() == RoleCode.STAFF && actor.getId().equals(owner.getId()))) return;
        throw new ApiException(ErrorCode.ACCESS_DENIED);
    }

    private void requireAdminOrManager(User actor) {
        if (!isManager(actor)) throw new ApiException(ErrorCode.ACCESS_DENIED);
    }

    private boolean isManager(User actor) {
        RoleCode role = actor.getRole().getCode();
        return role == RoleCode.MANAGER || role == RoleCode.ADMIN;
    }

    private void validateDates(StaffLicenseRequest request) {
        if (request.expiryDate() != null && request.expiryDate().isBefore(request.issuedDate())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Expiry date must not be before issue date");
        }
    }

    private void validateStatus(StaffLicenseRequest request) {
        if (request.licenseStatus() == LicenseStatus.REVOKED) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Use the revoke action to revoke a license");
        }
    }

    private void apply(StaffLicense license, StaffLicenseRequest request) {
        license.setLicenseType(request.licenseType());
        license.setLicenseNumber(request.licenseNumber().trim());
        license.setLicenseName(request.licenseName().trim());
        license.setIssuingAuthority(request.issuingAuthority().trim());
        license.setIssuedDate(request.issuedDate());
        license.setExpiryDate(request.expiryDate());
        license.setLicenseStatus(request.licenseStatus());
        license.setNotes(blankToNull(request.notes()));
    }

    private StaffLicenseResponse response(StaffLicense license) {
        boolean demo = isNonOperationalSeed(license);
        LicenseStatus effectiveStatus = license.getLicenseStatus();
        if (effectiveStatus == LicenseStatus.ACTIVE
                && license.getExpiryDate() != null
                && license.getExpiryDate().isBefore(java.time.LocalDate.now())) {
            effectiveStatus = LicenseStatus.EXPIRED;
        }
        return new StaffLicenseResponse(license.getId(), license.getUser().getId(),
                license.getUser().getFullName(), license.getLicenseType(), license.getLicenseNumber(),
                license.getLicenseName(), license.getIssuingAuthority(), license.getIssuedDate(), license.getExpiryDate(),
                effectiveStatus, license.getVerificationStatus(),
                license.getVerifiedBy() == null ? null : license.getVerifiedBy().getFullName(),
                license.getVerifiedAt(), license.getDocumentFileName(), license.getNotes(), demo);
    }

    private boolean isNonOperationalSeed(StaffLicense license) {
        String issuer = license.getIssuingAuthority();
        return DEVELOPMENT_SEED_ISSUER.equalsIgnoreCase(issuer)
                || LEGACY_ISSUER.equalsIgnoreCase(issuer);
    }

    private String validateDocument(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_DOCUMENT_BYTES) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Document must be non-empty and at most 10 MB");
        }
        try (var input = file.getInputStream()) {
            byte[] head = input.readNBytes(8);
            String type = null;
            if (head.length >= 5 && new String(head, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) type = "application/pdf";
            else if (head.length >= 8 && java.util.Arrays.equals(head,
                    new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) type = "image/png";
            else if (head.length >= 3 && (head[0] & 255) == 255 && (head[1] & 255) == 216 && (head[2] & 255) == 255) type = "image/jpeg";
            if (type == null || !type.equalsIgnoreCase(file.getContentType())) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "Only PDF, JPG and PNG files with matching signatures are supported");
            }
            return type;
        } catch (IOException failure) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Unable to read license document");
        }
    }

    private String safeFileName(String value) {
        if (value == null || value.isBlank()) return "license-document";
        String name = value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\r\\n]", "").trim();
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void scheduleCleanup(String oldBucket, String oldKey, String newBucket, String newKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED && oldKey != null) storage.deleteQuietly(oldBucket, oldKey);
                if (status == STATUS_ROLLED_BACK) storage.deleteQuietly(newBucket, newKey);
            }
        });
    }
}
