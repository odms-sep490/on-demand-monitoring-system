package com.ondemandmonitoring.user.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.repository.RoleRepository;
import com.ondemandmonitoring.user.domain.CustomerProfile;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.repository.CustomerProfileRepository;
import com.ondemandmonitoring.user.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class UserSeedDataInitializerTest {

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CustomerProfileRepository customerProfileRepository;

    @Test
    void run_provisionsCompleteCustomerProfiles() throws Exception {
        when(roleRepository.findByCode(any())).thenAnswer(invocation -> Optional.of(
                Role.builder()
                        .id(UUID.randomUUID())
                        .code(invocation.getArgument(0))
                        .name(invocation.getArgument(0).toString())
                        .active(true)
                        .build()));
        when(userRepository.findByEmailIgnoreCase(anyString())).thenAnswer(invocation -> {
            String email = invocation.getArgument(0);
            User customer = User.builder()
                    .email(email)
                    .fullName("Seed Customer")
                    .build();
            customer.setId(email);
            return Optional.of(customer);
        });
        new UserSeedDataInitializer(
                roleRepository, jdbcTemplate, userRepository, customerProfileRepository).run(null);

        ArgumentCaptor<CustomerProfile> profileCaptor =
                ArgumentCaptor.forClass(CustomerProfile.class);
        verify(customerProfileRepository, times(4)).save(profileCaptor.capture());
        assertThat(profileCaptor.getAllValues())
                .extracting(profile -> profile.getUser().getEmail())
                .containsExactly(
                        "seed.customer@odms.local",
                        "seed.customer.2@odms.local",
                        "seed.customer.3@odms.local",
                        "seed.customer.4@odms.local"
                );
        assertThat(profileCaptor.getAllValues())
                .allSatisfy(profile -> {
                    assertThat(profile.getPhoneNumber()).isNotBlank();
                    assertThat(profile.getAddress()).isNotBlank();
                    assertThat(profile.getCompanyName()).isNotBlank();
                    assertThat(profile.getCitizenIdentificationNumber()).hasSize(12);
                    assertThat(profile.getCompanyTaxCode()).isNotBlank();
                });

        verify(jdbcTemplate).update(org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("ON CONFLICT DO NOTHING") && !sql.contains("UPDATE")),
                eq("00000000-0000-0000-0000-000000000009"), eq("Seed Manager 2"),
                any(UUID.class), eq("seed.manager.2@odms.local"));
        verify(jdbcTemplate).update(org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("ON CONFLICT DO NOTHING") && !sql.contains("UPDATE")),
                eq("00000000-0000-0000-0000-000000000010"), eq("Seed Manager 3"),
                any(UUID.class), eq("seed.manager.3@odms.local"));
        verify(jdbcTemplate).update(org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("ON CONFLICT DO NOTHING") && !sql.contains("UPDATE")),
                eq("00000000-0000-0000-0000-000000000011"), eq("Seed Manager 4"),
                any(UUID.class), eq("seed.manager.4@odms.local"));
        verify(jdbcTemplate).update(org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("ON CONFLICT DO NOTHING") && !sql.contains("UPDATE")),
                eq("00000000-0000-0000-0000-000000000002"), eq("Seed Manager 1"),
                any(UUID.class), eq("seed.manager@odms.local"));
        verify(jdbcTemplate).update(org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("ON CONFLICT DO NOTHING") && !sql.contains("UPDATE")),
                eq("20000000-0000-0000-0000-000000000002"), eq("seed.manager@odms.local"),
                eq("LOCAL"), eq("e9eab58c-00a1-705d-69b7-c74a17074053"),
                eq("e9eab58c-00a1-705d-69b7-c74a17074053"));
    }
}
