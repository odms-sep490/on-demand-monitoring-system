package com.ondemandmonitoring.stafflicense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.stafflicense.domain.StaffLicense;
import com.ondemandmonitoring.stafflicense.dto.request.LicenseVerificationRequest;
import com.ondemandmonitoring.stafflicense.dto.request.StaffLicenseRequest;
import com.ondemandmonitoring.stafflicense.enumeration.LicenseStatus;
import com.ondemandmonitoring.stafflicense.enumeration.LicenseType;
import com.ondemandmonitoring.stafflicense.enumeration.VerificationStatus;
import com.ondemandmonitoring.stafflicense.repository.StaffLicenseRepository;
import com.ondemandmonitoring.stafflicense.service.impl.StaffLicenseServiceImpl;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.repository.UserRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Profile;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class StaffLicenseServiceTest {
    private final StaffLicenseRepository licenses = mock(StaffLicenseRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AuthenticatedUserResolver resolver = mock(AuthenticatedUserResolver.class);
    private final IMediaObjectStorage storage = mock(IMediaObjectStorage.class);
    private final StaffLicenseServiceImpl service = new StaffLicenseServiceImpl(licenses, users, resolver, storage);

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void createsLicenseForExistingStaffAndKeepsItUnverified() {
        User staff = user("staff-1", RoleCode.STAFF);
        when(resolver.getCurrentUser()).thenReturn(staff);
        when(users.findById("staff-1")).thenReturn(Optional.of(staff));
        when(licenses.existsByUser_IdAndLicenseNumberIgnoreCase("staff-1", "CERT-1")).thenReturn(false);
        when(licenses.save(any())).thenAnswer(call -> call.getArgument(0));

        var response = service.create(request("staff-1", LicenseStatus.ACTIVE));

        assertThat(response.userId()).isEqualTo("staff-1");
        assertThat(response.verificationStatus()).isEqualTo(VerificationStatus.PENDING);
        verify(licenses).save(any(StaffLicense.class));
    }

    @Test
    void doesNotAllowNonStaffAccountToOwnLicense() {
        User manager = user("manager-1", RoleCode.MANAGER);
        when(resolver.getCurrentUser()).thenReturn(manager);
        when(users.findById("manager-1")).thenReturn(Optional.of(manager));

        assertThatThrownBy(() -> service.create(request("manager-1", LicenseStatus.ACTIVE)))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);
        verify(licenses, never()).save(any());
    }

    @Test
    void missingStaffUserFailsWithoutCreatingOrphanLicense() {
        User manager = user("manager-1", RoleCode.MANAGER);
        when(resolver.getCurrentUser()).thenReturn(manager);
        when(users.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request("missing", LicenseStatus.ACTIVE)))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.USER_NOT_FOUND);
        verify(licenses, never()).save(any());
    }

    @Test
    void staffCannotReadAnotherStaffMembersLicenses() {
        User actor = user("staff-1", RoleCode.STAFF);
        User other = user("staff-2", RoleCode.STAFF);
        StaffLicense license = license(other);
        when(resolver.getCurrentUser()).thenReturn(actor);
        when(licenses.findWithUserById("license-1")).thenReturn(Optional.of(license));

        assertThatThrownBy(() -> service.get("license-1"))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ACCESS_DENIED);
    }

    @Test
    void expiredDateIsReflectedEvenWhenStoredStatusStillSaysActive() {
        User manager = user("manager-1", RoleCode.MANAGER);
        StaffLicense license = license(user("staff-1", RoleCode.STAFF));
        license.setExpiryDate(LocalDate.now().minusDays(1));
        when(resolver.getCurrentUser()).thenReturn(manager);
        when(licenses.findWithUserById("license-1")).thenReturn(Optional.of(license));

        assertThat(service.get("license-1").licenseStatus()).isEqualTo(LicenseStatus.EXPIRED);
    }

    @Test
    void assignedStaffLicenseListIsScopedToRequestedStaff() {
        User manager = user("manager-1", RoleCode.MANAGER);
        User assignedStaff = user("staff-2", RoleCode.STAFF);
        when(resolver.getCurrentUser()).thenReturn(manager);
        when(users.findById("staff-2")).thenReturn(Optional.of(assignedStaff));
        when(licenses.findAllByUser_IdOrderByLicenseTypeAscIssuedDateDesc("staff-2"))
                .thenReturn(List.of(license(assignedStaff)));

        var result = service.getForUser("staff-2");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).userId()).isEqualTo("staff-2");
        verify(licenses).findAllByUser_IdOrderByLicenseTypeAscIssuedDateDesc("staff-2");
    }

    @Test
    void validPdfUploadStoresFileAndResetsPriorVerification() {
        User staff = user("staff-1", RoleCode.STAFF);
        StaffLicense existing = license(staff);
        existing.setVerificationStatus(VerificationStatus.VERIFIED);
        when(resolver.getCurrentUser()).thenReturn(staff);
        when(licenses.findWithUserById("license-1")).thenReturn(Optional.of(existing));
        when(storage.bucket()).thenReturn("documents");
        when(licenses.save(any())).thenAnswer(call -> call.getArgument(0));
        TransactionSynchronizationManager.initSynchronization();
        MockMultipartFile pdf = new MockMultipartFile("file", "pilot.pdf", "application/pdf",
                "%PDF-1.7 sample".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        var result = service.uploadDocument("license-1", pdf);

        assertThat(result.documentFileName()).isEqualTo("pilot.pdf");
        assertThat(result.verificationStatus()).isEqualTo(VerificationStatus.PENDING);
        verify(storage).put(anyString(), eq("application/pdf"), eq(pdf.getSize()), any(), eq("staff-license-document"));
    }

    @Test
    void rejectsInvalidDocumentSignatureAndOversizedUpload() {
        User staff = user("staff-1", RoleCode.STAFF);
        when(resolver.getCurrentUser()).thenReturn(staff);
        when(licenses.findWithUserById("license-1")).thenReturn(Optional.of(license(staff)));
        MockMultipartFile mislabeled = new MockMultipartFile("file", "pilot.pdf", "application/pdf", "not a pdf".getBytes());
        assertThatThrownBy(() -> service.uploadDocument("license-1", mislabeled))
                .isInstanceOf(ApiException.class);
        MockMultipartFile oversized = new MockMultipartFile("file", "large.pdf", "application/pdf",
                new byte[10 * 1024 * 1024 + 1]);
        assertThatThrownBy(() -> service.uploadDocument("license-1", oversized))
                .isInstanceOf(ApiException.class);
        verify(storage, never()).put(anyString(), anyString(), anyLong(), any(), anyString());
    }

    @Test
    void managerCanVerifyAndRejectPendingLicense() {
        User manager = user("manager-1", RoleCode.MANAGER);
        User staff = user("staff-1", RoleCode.STAFF);
        StaffLicense license = license(staff);
        license.setDocumentStoragePath("staff-licenses/staff-1/certificate.pdf");
        license.setDocumentFileName("certificate.pdf");
        when(resolver.getCurrentUser()).thenReturn(manager);
        when(licenses.findWithUserById("license-1")).thenReturn(Optional.of(license));
        when(licenses.save(any())).thenAnswer(call -> call.getArgument(0));

        var result = service.verify("license-1", new LicenseVerificationRequest(VerificationStatus.REJECTED, "Chưa đủ bằng chứng"));

        assertThat(result.verificationStatus()).isEqualTo(VerificationStatus.REJECTED);
        assertThat(result.verifiedByName()).isEqualTo("manager-1");
        assertThat(result.verifiedAt()).isNotNull();
    }

    @Test
    void demoRecordsCannotBeMarkedAsVerifiedEvenWithAnUploadedFile() {
        User manager = user("manager-1", RoleCode.MANAGER);
        StaffLicense demoLicense = license(user("staff-1", RoleCode.STAFF));
        demoLicense.setIssuingAuthority("ODMS Demo Training Center — Not an Official Issuer");
        demoLicense.setDocumentStoragePath("staff-licenses/staff-1/demo.pdf");
        when(resolver.getCurrentUser()).thenReturn(manager);
        when(licenses.findWithUserById("license-1")).thenReturn(Optional.of(demoLicense));

        assertThatThrownBy(() -> service.verify("license-1",
                new LicenseVerificationRequest(VerificationStatus.VERIFIED, null)))
                .isInstanceOf(ApiException.class);
        verify(licenses, never()).save(any());
    }

    @Test
    void developmentSeedRecordsAreIdentifiedAndCannotUseTheRealVerificationWorkflow() {
        User manager = user("manager-1", RoleCode.MANAGER);
        StaffLicense seed = license(user("staff-1", RoleCode.STAFF));
        seed.setIssuingAuthority("ODMS Development Seed Registry — Not valid for aviation operations");
        seed.setDocumentStoragePath("staff-licenses/staff-1/certificate.pdf");
        seed.setDocumentFileName("certificate.pdf");
        seed.setVerificationStatus(VerificationStatus.VERIFIED);
        seed.setVerifiedBy(manager);
        seed.setVerifiedAt(java.time.LocalDateTime.of(2026, 8, 1, 9, 0));
        when(resolver.getCurrentUser()).thenReturn(manager);
        when(licenses.findWithUserById("license-1")).thenReturn(Optional.of(seed));

        var response = service.get("license-1");

        assertThat(response.licenseStatus()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(response.verificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(response.verifiedByName()).isEqualTo("manager-1");
        assertThat(response.verifiedAt()).isNotNull();
        assertThat(response.demoRecord()).isTrue();
        assertThatThrownBy(() -> service.verify("license-1",
                new LicenseVerificationRequest(VerificationStatus.REJECTED, "recheck")))
                .isInstanceOf(ApiException.class);
        verify(licenses, never()).save(any());
    }

    @Test
    void staffCannotVerifyOrRevokeLicenses() {
        User staff = user("staff-1", RoleCode.STAFF);
        when(resolver.getCurrentUser()).thenReturn(staff);
        when(licenses.findWithUserById("license-1")).thenReturn(Optional.of(license(staff)));

        assertThatThrownBy(() -> service.verify("license-1",
                new LicenseVerificationRequest(VerificationStatus.VERIFIED, null)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.revoke("license-1", "revoked"))
                .isInstanceOf(ApiException.class);
        verify(licenses, never()).save(any());
    }

    @Test
    void updateCannotTransferLicenseAndMustResetVerification() {
        User manager = user("manager-1", RoleCode.MANAGER);
        User staff1 = user("staff-1", RoleCode.STAFF);
        User staff2 = user("staff-2", RoleCode.STAFF);
        StaffLicense existing = license(staff1);
        existing.setVerificationStatus(VerificationStatus.VERIFIED);
        when(resolver.getCurrentUser()).thenReturn(manager);
        when(licenses.findWithUserById("license-1")).thenReturn(Optional.of(existing));
        when(users.findById("staff-2")).thenReturn(Optional.of(staff2));

        assertThatThrownBy(() -> service.update("license-1", request("staff-2", LicenseStatus.ACTIVE)))
                .isInstanceOf(ApiException.class);
        verify(licenses, never()).save(any());
    }

    @Test
    void staffCannotAccessExpiredDocumentOwnedBySomeoneElse() {
        User actor = user("staff-1", RoleCode.STAFF);
        StaffLicense otherLicense = license(user("staff-2", RoleCode.STAFF));
        otherLicense.setLicenseStatus(LicenseStatus.EXPIRED);
        otherLicense.setDocumentStoragePath("staff-licenses/staff-2/file.pdf");
        when(resolver.getCurrentUser()).thenReturn(actor);
        when(licenses.findWithUserById("license-1")).thenReturn(Optional.of(otherLicense));

        assertThatThrownBy(() -> service.getDocument("license-1"))
                .isInstanceOf(ApiException.class);
        verify(storage, never()).createPresignedGetUrl(anyString(), anyString());
    }

    private StaffLicenseRequest request(String userId, LicenseStatus status) {
        return new StaffLicenseRequest(userId, LicenseType.PILOT, "CERT-1", "Pilot certificate",
                "Training center", LocalDate.of(2026, 1, 1), LocalDate.of(2028, 1, 1), status, null);
    }

    private User user(String id, RoleCode roleCode) {
        User user = User.builder().fullName(id).role(Role.builder().code(roleCode).build()).isActive(true).build();
        user.setId(id);
        return user;
    }

    private StaffLicense license(User owner) {
        StaffLicense license = new StaffLicense();
        license.setId("license-1");
        license.setUser(owner);
        license.setLicenseType(LicenseType.PILOT);
        license.setLicenseNumber("CERT-1");
        license.setLicenseName("Pilot certificate");
        license.setIssuingAuthority("Training center");
        license.setIssuedDate(LocalDate.of(2026, 1, 1));
        license.setExpiryDate(LocalDate.of(2028, 1, 1));
        license.setLicenseStatus(LicenseStatus.ACTIVE);
        license.setVerificationStatus(VerificationStatus.PENDING);
        return license;
    }
}
