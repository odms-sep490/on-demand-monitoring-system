package com.ondemandmonitoring.stafflicense.config;

import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.stafflicense.domain.StaffLicense;
import com.ondemandmonitoring.stafflicense.enumeration.LicenseStatus;
import com.ondemandmonitoring.stafflicense.enumeration.LicenseType;
import com.ondemandmonitoring.stafflicense.enumeration.VerificationStatus;
import com.ondemandmonitoring.stafflicense.repository.StaffLicenseRepository;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(30)
@Profile("!prod & !production & (dev | development | local | test | demo)")
@ConditionalOnProperty(name = "app.seed.staff-licenses", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class StaffLicenseSeedDataInitializer implements ApplicationRunner {
    static final String DEVELOPMENT_SEED_ISSUER =
            "ODMS Development Seed Registry — Not valid for aviation operations";
    static final String LEGACY_ISSUER = "ODMS Demo Training Center — Not an Official Issuer";
    private static final String VERIFIER_ID = "00000000-0000-0000-0000-000000000002";
    private static final LocalDateTime VERIFIED_AT = LocalDateTime.of(2026, 8, 1, 9, 0);

    private static final List<SeedLicense> SEEDS = List.of(
            new SeedLicense("00000000-0000-0000-0000-000000000003", "UAV-PILOT-2026-001",
                    "DEMO-UAV-PILOT-001", "UAV Pilot Competency Certificate", LicenseType.PILOT,
                    LocalDate.of(2026, 1, 15), LocalDate.of(2029, 1, 15)),
            new SeedLicense("00000000-0000-0000-0000-000000000012", "UAV-PILOT-2026-002",
                    "DEMO-UAV-PILOT-002", "UAV Pilot Competency Certificate", LicenseType.PILOT,
                    LocalDate.of(2026, 3, 1), LocalDate.of(2029, 3, 1)),
            new SeedLicense("00000000-0000-0000-0000-000000000013", "UAV-PILOT-2026-003",
                    "DEMO-UAV-PILOT-003", "UAV Pilot Competency Certificate", LicenseType.PILOT,
                    LocalDate.of(2026, 5, 10), LocalDate.of(2029, 5, 10)),
            new SeedLicense("00000000-0000-0000-0000-000000000014", "UAV-PILOT-2026-004",
                    "DEMO-UAV-PILOT-004", "UAV Pilot Competency Certificate", LicenseType.PILOT,
                    LocalDate.of(2026, 7, 1), LocalDate.of(2029, 7, 1)),
            new SeedLicense("00000000-0000-0000-0000-000000000004", "UAV-TECH-2026-005",
                    "DEMO-UAV-TECH-005", "UAV Maintenance Competency Certificate", LicenseType.TECHNICAL,
                    LocalDate.of(2026, 2, 1), LocalDate.of(2029, 2, 1)));

    private final UserRepository users;
    private final StaffLicenseRepository licenses;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        User verifier = users.findById(VERIFIER_ID)
                .filter(this::isManager)
                .orElse(null);

        for (SeedLicense seed : SEEDS) {
            User staff = users.findById(seed.userId()).orElse(null);
            if (staff == null || staff.getRole().getCode() != RoleCode.STAFF) {
                log.warn("Skipping staff license seed because the expected staff account is missing: {}", seed.userId());
                continue;
            }

            StaffLicense current = licenses
                    .findByUser_IdAndLicenseNumberIgnoreCase(seed.userId(), seed.number())
                    .orElse(null);
            if (current != null && !isSeedRecord(current)) {
                log.warn("Preserving an existing staff license with a matching number: {}", seed.number());
                continue;
            }

            StaffLicense legacy = licenses
                    .findByUser_IdAndLicenseNumberIgnoreCase(seed.userId(), seed.legacyNumber())
                    .orElse(null);
            if (legacy != null && !isSeedRecord(legacy)) {
                legacy = null;
            }

            StaffLicense license = current != null ? current : legacy;
            if (license == null) {
                license = new StaffLicense();
                license.setUser(staff);
            } else if (current != null && legacy != null && !Objects.equals(current.getId(), legacy.getId())) {
                // Keep any real attachment while removing only the known legacy seed row.
                if (current.getDocumentStoragePath() == null) {
                    current.setDocumentStoragePath(legacy.getDocumentStoragePath());
                    current.setDocumentFileName(legacy.getDocumentFileName());
                }
                licenses.delete(legacy);
            }

            license.setUser(staff);
            license.setLicenseNumber(seed.number());
            license.setLicenseName(seed.name());
            license.setLicenseType(seed.type());
            license.setIssuingAuthority(DEVELOPMENT_SEED_ISSUER);
            license.setIssuedDate(seed.issued());
            license.setExpiryDate(seed.expiry());
            license.setLicenseStatus(LicenseStatus.ACTIVE);
            license.setVerificationStatus(VerificationStatus.VERIFIED);
            license.setVerifiedBy(verifier);
            license.setVerifiedAt(VERIFIED_AT);
            license.setNotes(null);
            // No sample certificate file is fabricated. Existing uploaded files are preserved.
            licenses.save(license);
        }
    }

    private boolean isManager(User user) {
        RoleCode role = user.getRole().getCode();
        return role == RoleCode.MANAGER || role == RoleCode.ADMIN;
    }

    private boolean isSeedRecord(StaffLicense license) {
        String issuer = license.getIssuingAuthority();
        return DEVELOPMENT_SEED_ISSUER.equalsIgnoreCase(issuer)
                || LEGACY_ISSUER.equalsIgnoreCase(issuer);
    }

    private record SeedLicense(String userId, String number, String legacyNumber, String name,
            LicenseType type, LocalDate issued, LocalDate expiry) {}
}
