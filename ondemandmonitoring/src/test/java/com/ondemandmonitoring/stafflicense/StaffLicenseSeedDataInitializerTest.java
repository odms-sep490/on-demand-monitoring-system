package com.ondemandmonitoring.stafflicense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.stafflicense.config.StaffLicenseSeedDataInitializer;
import com.ondemandmonitoring.stafflicense.domain.StaffLicense;
import com.ondemandmonitoring.stafflicense.enumeration.LicenseStatus;
import com.ondemandmonitoring.stafflicense.enumeration.LicenseType;
import com.ondemandmonitoring.stafflicense.enumeration.VerificationStatus;
import com.ondemandmonitoring.stafflicense.repository.StaffLicenseRepository;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;

class StaffLicenseSeedDataInitializerTest {
    private static final List<String> STAFF_IDS = List.of(
            "00000000-0000-0000-0000-000000000003",
            "00000000-0000-0000-0000-000000000012",
            "00000000-0000-0000-0000-000000000013",
            "00000000-0000-0000-0000-000000000014",
            "00000000-0000-0000-0000-000000000004");
    private static final String MANAGER_ID = "00000000-0000-0000-0000-000000000002";

    @Test
    void seedsEveryExistingStaffWithCompleteVerifiedDevelopmentRecords() {
        Fixture fixture = fixture(Set.of(), List.of());
        fixture.initializer().run(mock(ApplicationArguments.class));

        assertThat(fixture.saved()).hasSize(5);
        assertThat(fixture.saved()).extracting(item -> item.getUser().getId())
                .containsExactlyElementsOf(STAFF_IDS);
        assertThat(fixture.saved()).allSatisfy(item -> {
            assertThat(item.getLicenseName()).isIn(
                    "UAV Pilot Competency Certificate", "UAV Maintenance Competency Certificate");
            assertThat(item.getLicenseNumber()).matches("UAV-(PILOT|TECH)-2026-\\d{3}");
            assertThat(item.getIssuingAuthority())
                    .isEqualTo("ODMS Development Seed Registry — Not valid for aviation operations");
            assertThat(item.getIssuedDate()).isNotNull();
            assertThat(item.getExpiryDate()).isAfter(LocalDate.now());
            assertThat(item.getLicenseStatus()).isEqualTo(LicenseStatus.ACTIVE);
            assertThat(item.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
            assertThat(item.getVerifiedAt()).isEqualTo(LocalDateTime.of(2026, 8, 1, 9, 0));
            assertThat(item.getVerifiedBy().getId()).isEqualTo(MANAGER_ID);
            assertThat(item.getDocumentStoragePath()).isNull();
            assertThat(item.getDocumentFileName()).isNull();
            assertThat(item.getNotes()).isNull();
        });
        assertThat(fixture.saved()).filteredOn(item -> item.getLicenseType() == LicenseType.TECHNICAL)
                .hasSize(1);
        assertThat(fixture.saved()).extracting(StaffLicense::getLicenseNumber)
                .doesNotContain("DEMO-UAV-PILOT-001", "DEMO-UAV-PILOT-002", "DEMO-UAV-PILOT-003",
                        "DEMO-UAV-PILOT-004", "DEMO-UAV-TECH-005");
    }

    @Test
    void normalizesOnlyKnownLegacySeedRowsAndPreservesTheirAttachment() {
        User staff = user(STAFF_IDS.get(0), RoleCode.STAFF);
        StaffLicense legacy = new StaffLicense();
        legacy.setId("legacy-license-id");
        legacy.setUser(staff);
        legacy.setLicenseNumber("DEMO-UAV-PILOT-001");
        legacy.setLicenseName("UAV Pilot Competency Certificate (Demo)");
        legacy.setLicenseType(LicenseType.PILOT);
        legacy.setIssuingAuthority("ODMS Demo Training Center — Not an Official Issuer");
        legacy.setIssuedDate(LocalDate.of(2026, 1, 15));
        legacy.setExpiryDate(LocalDate.of(2029, 1, 15));
        legacy.setLicenseStatus(LicenseStatus.ACTIVE);
        legacy.setVerificationStatus(VerificationStatus.PENDING);
        legacy.setDocumentStoragePath("staff-licenses/seed/certificate.pdf");
        legacy.setDocumentFileName("certificate.pdf");
        Fixture fixture = fixture(Set.of(), List.of(legacy));

        fixture.initializer().run(mock(ApplicationArguments.class));

        StaffLicense normalized = fixture.saved().stream()
                .filter(item -> item.getUser().getId().equals(STAFF_IDS.get(0)))
                .findFirst().orElseThrow();
        assertThat(fixture.saved()).hasSize(5);
        assertThat(normalized.getId()).isEqualTo("legacy-license-id");
        assertThat(normalized.getLicenseNumber()).isEqualTo("UAV-PILOT-2026-001");
        assertThat(normalized.getLicenseName()).isEqualTo("UAV Pilot Competency Certificate");
        assertThat(normalized.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(normalized.getDocumentStoragePath()).isEqualTo("staff-licenses/seed/certificate.pdf");
        assertThat(normalized.getDocumentFileName()).isEqualTo("certificate.pdf");
    }

    @Test
    void repeatedRunsDoNotDuplicateRecordsAndRefreshOnlySeedOwnedValues() {
        Fixture fixture = fixture(Set.of(), List.of());
        fixture.initializer().run(mock(ApplicationArguments.class));
        fixture.saved().get(0).setLicenseName("Changed seed data");

        fixture.initializer().run(mock(ApplicationArguments.class));

        assertThat(fixture.saved()).hasSize(5);
        assertThat(fixture.saved().get(0).getLicenseName())
                .isEqualTo("UAV Pilot Competency Certificate");
    }

    @Test
    void doesNotOverwriteARealLicenseWithTheReservedSeedNumber() {
        User staff = user(STAFF_IDS.get(0), RoleCode.STAFF);
        StaffLicense real = new StaffLicense();
        real.setId("real-license-id");
        real.setUser(staff);
        real.setLicenseNumber("UAV-PILOT-2026-001");
        real.setLicenseName("Pilot Competency Certificate");
        real.setLicenseType(LicenseType.PILOT);
        real.setIssuingAuthority("Civil Aviation Authority");
        real.setIssuedDate(LocalDate.of(2026, 1, 1));
        real.setExpiryDate(LocalDate.of(2028, 1, 1));
        real.setLicenseStatus(LicenseStatus.SUSPENDED);
        real.setVerificationStatus(VerificationStatus.PENDING);
        Fixture fixture = fixture(Set.of(), List.of(real));

        fixture.initializer().run(mock(ApplicationArguments.class));

        StaffLicense preserved = fixture.saved().stream()
                .filter(item -> item.getUser().getId().equals(STAFF_IDS.get(0)))
                .findFirst().orElseThrow();
        assertThat(preserved.getId()).isEqualTo("real-license-id");
        assertThat(preserved.getLicenseName()).isEqualTo("Pilot Competency Certificate");
        assertThat(preserved.getLicenseStatus()).isEqualTo(LicenseStatus.SUSPENDED);
        assertThat(preserved.getVerificationStatus()).isEqualTo(VerificationStatus.PENDING);
        assertThat(fixture.saved()).hasSize(5);
    }

    @Test
    void doesNotTreatARealLicenseAsSeedOwnedBasedOnlyOnItsLegacyLookingNumber() {
        User staff = user(STAFF_IDS.get(0), RoleCode.STAFF);
        StaffLicense real = new StaffLicense();
        real.setId("real-legacy-number-license-id");
        real.setUser(staff);
        real.setLicenseNumber("DEMO-UAV-PILOT-001");
        real.setLicenseName("Pilot Competency Certificate");
        real.setLicenseType(LicenseType.PILOT);
        real.setIssuingAuthority("Civil Aviation Authority");
        real.setIssuedDate(LocalDate.of(2026, 1, 1));
        real.setExpiryDate(LocalDate.of(2028, 1, 1));
        real.setLicenseStatus(LicenseStatus.SUSPENDED);
        real.setVerificationStatus(VerificationStatus.PENDING);
        Fixture fixture = fixture(Set.of(), List.of(real));

        fixture.initializer().run(mock(ApplicationArguments.class));

        StaffLicense preserved = fixture.saved().stream()
                .filter(item -> "real-legacy-number-license-id".equals(item.getId()))
                .findFirst().orElseThrow();
        assertThat(preserved.getLicenseName()).isEqualTo("Pilot Competency Certificate");
        assertThat(preserved.getIssuingAuthority()).isEqualTo("Civil Aviation Authority");
        assertThat(preserved.getVerificationStatus()).isEqualTo(VerificationStatus.PENDING);
        assertThat(fixture.saved()).hasSize(6);
    }

    @Test
    void skipsMissingOrNonStaffUsersWithoutCreatingOrphanRecords() {
        Fixture fixture = fixture(Set.of(STAFF_IDS.get(0)), List.of());
        fixture.initializer().run(mock(ApplicationArguments.class));

        assertThat(fixture.saved()).hasSize(4);
        assertThat(fixture.saved()).noneMatch(item -> item.getUser().getId().equals(STAFF_IDS.get(0)));
    }

    @Test
    void initializerIsEnabledOnlyForNonProductionProfilesAndCanBeDisabled() {
        Profile profile = StaffLicenseSeedDataInitializer.class.getAnnotation(Profile.class);
        assertThat(profile).isNotNull();
        assertThat(profile.value()[0]).contains("!prod", "!production", "dev", "development", "local", "test");
        ConditionalOnProperty property = StaffLicenseSeedDataInitializer.class
                .getAnnotation(ConditionalOnProperty.class);
        assertThat(property).isNotNull();
        assertThat(property.name()).contains("app.seed.staff-licenses");
        assertThat(property.havingValue()).isEqualTo("true");
    }

    private Fixture fixture(Set<String> missingUsers, List<StaffLicense> initialLicenses) {
        UserRepository users = mock(UserRepository.class);
        StaffLicenseRepository licenses = mock(StaffLicenseRepository.class);
        List<StaffLicense> saved = new ArrayList<>(initialLicenses);
        User manager = user(MANAGER_ID, RoleCode.MANAGER);
        when(users.findById(MANAGER_ID)).thenReturn(Optional.of(manager));
        for (String id : STAFF_IDS) {
            if (!missingUsers.contains(id)) {
                when(users.findById(id)).thenReturn(Optional.of(user(id, RoleCode.STAFF)));
            }
        }
        when(licenses.findByUser_IdAndLicenseNumberIgnoreCase(any(), any())).thenAnswer(call -> {
            String userId = call.getArgument(0);
            String number = call.getArgument(1);
            return saved.stream()
                    .filter(item -> item.getUser().getId().equals(userId))
                    .filter(item -> item.getLicenseNumber().equalsIgnoreCase(number))
                    .findFirst();
        });
        when(licenses.save(any())).thenAnswer(call -> {
            StaffLicense license = call.getArgument(0);
            if (license.getId() == null) license.setId(UUID.randomUUID().toString());
            saved.removeIf(existing -> license.getId().equals(existing.getId()));
            saved.add(license);
            return license;
        });
        return new Fixture(new StaffLicenseSeedDataInitializer(users, licenses), saved);
    }

    private User user(String id, RoleCode roleCode) {
        User user = User.builder().fullName("Seed account").isActive(true)
                .role(Role.builder().code(roleCode).build()).build();
        user.setId(id);
        return user;
    }

    private record Fixture(StaffLicenseSeedDataInitializer initializer, List<StaffLicense> saved) {}
}
