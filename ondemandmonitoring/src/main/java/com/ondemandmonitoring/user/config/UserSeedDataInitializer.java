package com.ondemandmonitoring.user.config;

import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.role.repository.RoleRepository;
import com.ondemandmonitoring.user.domain.CustomerProfile;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.enumeration.IdentityProvider;
import com.ondemandmonitoring.user.repository.CustomerProfileRepository;
import com.ondemandmonitoring.user.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(20)
@RequiredArgsConstructor
public class UserSeedDataInitializer implements ApplicationRunner {

    private static final List<SeedUser> SEED_USERS = List.of(
            new SeedUser(
                    "00000000-0000-0000-0000-000000000001",
                    "Seed Customer 1",
                    RoleCode.CUSTOMER,
                    "seed.customer@odms.local",
                    "20000000-0000-0000-0000-000000000001",
                    "39ea75ec-c001-7087-152e-e76ebbf4740b",
                    new CustomerSeedProfile("0901234567", "123 Nguyễn Huệ, phường Sài Gòn, TP.HCM",
                            "Công ty TNHH Xây dựng Minh Anh", "079203001234", "0317654321")
            ),
            new SeedUser(
                    "00000000-0000-0000-0000-000000000006",
                    "Seed Customer 2",
                    RoleCode.CUSTOMER,
                    "seed.customer.2@odms.local",
                    "20000000-0000-0000-0000-000000000006",
                    "79cad52c-90e1-7069-5054-4d5073f30ea0",
                    new CustomerSeedProfile("0912345678", "45 Võ Văn Tần, phường Xuân Hòa, TP.HCM",
                            "Công ty Cổ phần Khảo sát Địa Việt", "079198005678", "0318765432")
            ),
            new SeedUser(
                    "00000000-0000-0000-0000-000000000007",
                    "Seed Customer 3",
                    RoleCode.CUSTOMER,
                    "seed.customer.3@odms.local",
                    "20000000-0000-0000-0000-000000000007",
                    "b9ba05cc-70c1-707b-37d6-12c83bd919ae",
                    new CustomerSeedProfile("0987654321", "88 Nguyễn Văn Linh, phường Tân Hưng, TP.HCM",
                            "Công ty TNHH Nông nghiệp Xanh", "079205009876", "0319876543")
            ),
            new SeedUser(
                    "00000000-0000-0000-0000-000000000008",
                    "Seed Customer 4",
                    RoleCode.CUSTOMER,
                    "seed.customer.4@odms.local",
                    "20000000-0000-0000-0000-000000000008",
                    "395a554c-9071-7082-89df-f0675ae6a6fe",
                    new CustomerSeedProfile("0934567890", "16 Mai Chí Thọ, phường An Khánh, TP.HCM",
                            "Công ty Cổ phần Hạ tầng Thành Phố", "079190004321", "0316543210")
            ),
            new SeedUser(
                    "00000000-0000-0000-0000-000000000002",
                    "Seed Manager 1",
                    RoleCode.MANAGER,
                    "seed.manager@odms.local",
                    "20000000-0000-0000-0000-000000000002",
                    "e9eab58c-00a1-705d-69b7-c74a17074053"
            ),
            new SeedUser(
                    "00000000-0000-0000-0000-000000000009",
                    "Seed Manager 2",
                    RoleCode.MANAGER,
                    "seed.manager.2@odms.local",
                    "20000000-0000-0000-0000-000000000009",
                    "f97ab50c-9071-70ad-2df1-9009bcea418d"
            ),
            new SeedUser(
                    "00000000-0000-0000-0000-000000000010",
                    "Seed Manager 3",
                    RoleCode.MANAGER,
                    "seed.manager.3@odms.local",
                    "20000000-0000-0000-0000-000000000010",
                    "19dae58c-2091-706d-6499-53583c362c64"
            ),
            new SeedUser(
                    "00000000-0000-0000-0000-000000000011",
                    "Seed Manager 4",
                    RoleCode.MANAGER,
                    "seed.manager.4@odms.local",
                    "20000000-0000-0000-0000-000000000011",
                    "895a956c-30d1-70ba-6a53-e0c9372084e3"
            ),
            new SeedUser(
                    "00000000-0000-0000-0000-000000000003",
                    "Seed Staff 1",
                    RoleCode.STAFF,
                    "seed.drone.operator@odms.local",
                    "20000000-0000-0000-0000-000000000003",
                    "c9cab55c-0081-705a-a1e0-4358cd45d47e"
            ),
            new SeedUser(
                    "00000000-0000-0000-0000-000000000012",
                    "Seed Staff 2",
                    RoleCode.STAFF,
                    "seed.drone.operator.2@odms.local",
                    "20000000-0000-0000-0000-000000000012",
                    "b9da65dc-20c1-70b1-2f9e-97de9e75be80"
            ),
            new SeedUser(
                    "00000000-0000-0000-0000-000000000013",
                    "Seed Staff 3",
                    RoleCode.STAFF,
                    "seed.drone.operator.3@odms.local",
                    "20000000-0000-0000-0000-000000000013",
                    "698ac5bc-7081-7030-37eb-4dd912d0f2d8"
            ),
            new SeedUser(
                    "00000000-0000-0000-0000-000000000014",
                    "Seed Staff 4",
                    RoleCode.STAFF,
                    "seed.drone.operator.4@odms.local",
                    "20000000-0000-0000-0000-000000000014",
                    "c9aa556c-e001-70e7-340a-df3b92f2a208"
            ),
            new SeedUser(
                    "00000000-0000-0000-0000-000000000004",
                    "Seed Staff Technician",
                    RoleCode.STAFF,
                    "seed.system.operator@odms.local",
                    "20000000-0000-0000-0000-000000000004",
                    "792ab50c-9061-7069-6f70-b6729473926c"
            ),
            new SeedUser(
                    "00000000-0000-0000-0000-000000000005",
                    "Seed Admin",
                    RoleCode.ADMIN,
                    "seed.admin@odms.local",
                    "20000000-0000-0000-0000-000000000005",
                    "e9fa959c-3041-70ef-b624-595c74207d06"
            )
    );

    private final RoleRepository roleRepository;
    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;
    private final CustomerProfileRepository customerProfileRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (SeedUser seed : SEED_USERS) {
            Role role = roleRepository.findByCode(seed.role())
                    .orElseThrow(() ->
                            new IllegalStateException(
                                    "Missing seed role: " + seed.role()
                            )
                    );

            insertUser(seed, role);
            seedStaffPhone(seed);
            insertIdentity(seed);
            provisionCustomerProfile(seed);
        }
    }

    private void seedStaffPhone(SeedUser seed) {
        String phoneNumber = switch (seed.email()) {
            case "seed.manager@odms.local" -> "0902000001";
            case "seed.manager.2@odms.local" -> "0902000002";
            case "seed.manager.3@odms.local" -> "0902000003";
            case "seed.manager.4@odms.local" -> "0902000004";
            case "seed.drone.operator@odms.local" -> "0901000001";
            case "seed.drone.operator.2@odms.local" -> "0901000002";
            case "seed.drone.operator.3@odms.local" -> "0901000003";
            case "seed.drone.operator.4@odms.local" -> "0901000004";
            case "seed.system.operator@odms.local" -> "0901000005";
            default -> null;
        };
        if (phoneNumber == null) {
            return;
        }
        jdbcTemplate.update(
                "UPDATE users SET phone_number = ? WHERE id = ? AND phone_number IS NULL",
                phoneNumber,
                seed.userId()
        );
    }

    private void provisionCustomerProfile(SeedUser seed) {
        if (seed.role() != RoleCode.CUSTOMER) {
            return;
        }

        User user = userRepository.findByEmailIgnoreCase(seed.email())
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Missing seeded customer after insert: "
                                        + seed.email()
                        )
                );

        CustomerProfile profile = customerProfileRepository.findById(user.getId())
                .orElseGet(() -> CustomerProfile.builder().user(user).build());
        CustomerSeedProfile data = seed.customerProfile();
        if (data == null) {
            throw new IllegalStateException("Missing customer seed profile: " + seed.email());
        }
        profile.setPhoneNumber(data.phoneNumber());
        profile.setAddress(data.address());
        profile.setCompanyName(data.companyName());
        profile.setCitizenIdentificationNumber(data.citizenIdentificationNumber());
        profile.setCompanyTaxCode(data.companyTaxCode());
        customerProfileRepository.save(profile);
    }

    private void insertUser(SeedUser seed, Role role) {
        jdbcTemplate.update("""
                INSERT INTO users (
                    id,
                    full_name,
                    role_id,
                    email,
                    email_verified,
                    is_active,
                    version,
                    created_at,
                    updated_at
                )
                VALUES (
                    ?, ?, ?, ?,
                    true,
                    true,
                    0,
                    CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP
                )
                ON CONFLICT DO NOTHING
                """,
                seed.userId(),
                seed.fullName(),
                role.getId(),
                seed.email()
        );
    }

    private void insertIdentity(SeedUser seed) {
        jdbcTemplate.update("""
            INSERT INTO user_identities (
                id,
                user_id,
                provider,
                cognito_username,
                cognito_sub,
                version,
                created_at,
                updated_at
            )
            VALUES (
                ?, (SELECT id FROM users WHERE email = ?), ?, ?, ?,
                0,
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP
            )
            ON CONFLICT DO NOTHING
            """,
                seed.identityId(),
                seed.email(),
                IdentityProvider.LOCAL.name(),
                seed.cognitoSub(),
                seed.cognitoSub()
        );
    }
    private record SeedUser(
            String userId,
            String fullName,
            RoleCode role,
            String email,
            String identityId,
            String cognitoSub,
            CustomerSeedProfile customerProfile
    ) {
        private SeedUser(
                String userId,
                String fullName,
                RoleCode role,
                String email,
                String identityId,
                String cognitoSub) {
            this(userId, fullName, role, email, identityId, cognitoSub, null);
        }
    }

    private record CustomerSeedProfile(
            String phoneNumber,
            String address,
            String companyName,
            String citizenIdentificationNumber,
            String companyTaxCode
    ) {
    }
}
