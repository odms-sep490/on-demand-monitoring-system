package com.ondemandmonitoring.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.CustomerProfile;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.dto.request.UserProfileUpdateRequest;
import com.ondemandmonitoring.user.dto.response.UserProfileResponse;
import com.ondemandmonitoring.user.mapper.UserProfileMapper;
import com.ondemandmonitoring.user.repository.CustomerProfileRepository;
import com.ondemandmonitoring.user.repository.UserRepository;
import com.ondemandmonitoring.user.repository.UserIdentityRepository;
import com.ondemandmonitoring.user.domain.UserIdentity;
import com.ondemandmonitoring.user.enumeration.IdentityProvider;
import java.util.List;
import com.ondemandmonitoring.user.service.impl.UserProfileServiceImpl;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mapstruct.factory.Mappers;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceImplTest {

    @Mock
    private AuthenticatedUserResolver authenticatedUserResolver;

    @Mock
    private CustomerProfileRepository customerProfileRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserIdentityRepository userIdentityRepository;

    private UserProfileServiceImpl userProfileService;

    @BeforeEach
    void setUp() {
        userProfileService = new UserProfileServiceImpl(
                authenticatedUserResolver,
                customerProfileRepository,
                userRepository,
                Mappers.getMapper(UserProfileMapper.class),
                userIdentityRepository);
    }

    @Test
    void profileReturnsActualLinkedProvidersFromDatabase() {
        User user = user(RoleCode.STAFF);
        when(authenticatedUserResolver.getCurrentUser()).thenReturn(user);
        when(userIdentityRepository.findAllByUserId(user.getId())).thenReturn(List.of(
                UserIdentity.builder().provider(IdentityProvider.GOOGLE).build()));
        assertThat(userProfileService.getCurrentProfile().getLinkedProviders())
                .containsExactly(IdentityProvider.GOOGLE);
        when(userIdentityRepository.findAllByUserId(user.getId())).thenReturn(List.of(
                UserIdentity.builder().provider(IdentityProvider.GOOGLE).build(),
                UserIdentity.builder().provider(IdentityProvider.LOCAL).build()));
        assertThat(userProfileService.getCurrentProfile().getLinkedProviders())
                .containsExactlyInAnyOrder(IdentityProvider.GOOGLE, IdentityProvider.LOCAL);
    }

    @Test
    void getCurrentProfile_customer_returnsCommonAndCustomerFields() {
        User user = user(RoleCode.CUSTOMER);
        CustomerProfile customerProfile = CustomerProfile.builder()
                .user(user)
                .phoneNumber("0901234567")
                .address("Ho Chi Minh City")
                .companyName("ODMS Customer")
                .build();
        when(authenticatedUserResolver.getCurrentUser()).thenReturn(user);
        when(customerProfileRepository.findById(user.getId()))
                .thenReturn(Optional.of(customerProfile));

        UserProfileResponse response = userProfileService.getCurrentProfile();

        assertThat(response.getId()).isEqualTo(user.getId());
        assertThat(response.getEmail()).isEqualTo(user.getEmail());
        assertThat(response.getFullName()).isEqualTo(user.getFullName());
        assertThat(response.getRole()).isEqualTo(RoleCode.CUSTOMER);
        assertThat(response.getCustomerProfile().getPhoneNumber()).isEqualTo("0901234567");
        assertThat(response.getCustomerProfile().getAddress()).isEqualTo("Ho Chi Minh City");
        assertThat(response.getCustomerProfile().getCompanyName()).isEqualTo("ODMS Customer");
    }

    @Test
    void getCurrentProfile_employee_returnsOnlyCommonFields() {
        User user = user(RoleCode.MANAGER);
        when(authenticatedUserResolver.getCurrentUser()).thenReturn(user);

        UserProfileResponse response = userProfileService.getCurrentProfile();

        assertThat(response.getRole()).isEqualTo(RoleCode.MANAGER);
        assertThat(response.getCustomerProfile()).isNull();
        verify(customerProfileRepository, never()).findById(user.getId());
    }

    @Test
    void getCurrentProfile_customerWithoutProfile_throwsResourceNotFound() {
        User user = user(RoleCode.CUSTOMER);
        when(authenticatedUserResolver.getCurrentUser()).thenReturn(user);
        when(customerProfileRepository.findById(user.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(userProfileService::getCurrentProfile)
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void updateCurrentProfile_customer_updatesAndNormalizesProvidedFields() {
        User user = user(RoleCode.CUSTOMER);
        CustomerProfile customerProfile = CustomerProfile.builder()
                .user(user)
                .phoneNumber("0900000000")
                .address("Old address")
                .companyName("Old company")
                .build();
        when(authenticatedUserResolver.getCurrentUser()).thenReturn(user);
        when(customerProfileRepository.findById(user.getId()))
                .thenReturn(Optional.of(customerProfile));
        UserProfileUpdateRequest request = UserProfileUpdateRequest.builder()
                .fullName("  Updated Name  ")
                .phoneNumber("+84 901 234 567")
                .address("")
                .companyName("  Updated Company  ")
                .build();

        UserProfileResponse response = userProfileService.updateCurrentProfile(request);

        assertThat(user.getFullName()).isEqualTo("Updated Name");
        assertThat(customerProfile.getPhoneNumber()).isEqualTo("+84 901 234 567");
        assertThat(customerProfile.getAddress()).isNull();
        assertThat(customerProfile.getCompanyName()).isEqualTo("Updated Company");
        assertThat(response.getFullName()).isEqualTo("Updated Name");
        assertThat(response.getCustomerProfile().getAddress()).isNull();
        verify(userRepository).save(user);
        verify(customerProfileRepository).save(customerProfile);
    }

    @Test
    void updateCurrentProfile_customerPartialUpdate_preservesUnspecifiedFields() {
        User user = user(RoleCode.CUSTOMER);
        CustomerProfile customerProfile = CustomerProfile.builder()
                .user(user)
                .phoneNumber("0900000000")
                .address("Existing address")
                .companyName("Existing company")
                .build();
        when(authenticatedUserResolver.getCurrentUser()).thenReturn(user);
        when(customerProfileRepository.findById(user.getId()))
                .thenReturn(Optional.of(customerProfile));

        userProfileService.updateCurrentProfile(UserProfileUpdateRequest.builder()
                .address("New address")
                .build());

        assertThat(customerProfile.getPhoneNumber()).isEqualTo("0900000000");
        assertThat(customerProfile.getAddress()).isEqualTo("New address");
        assertThat(customerProfile.getCompanyName()).isEqualTo("Existing company");
        verify(userRepository, never()).save(user);
    }

    @Test
    void updateCurrentProfile_employee_canUpdateCommonFields() {
        User user = user(RoleCode.MANAGER);
        when(authenticatedUserResolver.getCurrentUser()).thenReturn(user);

        UserProfileResponse response = userProfileService.updateCurrentProfile(
                UserProfileUpdateRequest.builder().fullName("Updated Staff").build());

        assertThat(response.getFullName()).isEqualTo("Updated Staff");
        assertThat(response.getCustomerProfile()).isNull();
        verify(userRepository).save(user);
        verify(customerProfileRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void updateCurrentProfile_employeeCannotUpdateCustomerFields() {
        User user = user(RoleCode.MANAGER);
        when(authenticatedUserResolver.getCurrentUser()).thenReturn(user);

        assertThatThrownBy(() -> userProfileService.updateCurrentProfile(
                UserProfileUpdateRequest.builder().companyName("Not allowed").build()))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_REQUEST));
        verify(userRepository, never()).save(user);
    }

    @Test
    void updateCurrentProfile_emptyRequest_throwsInvalidRequest() {
        assertThatThrownBy(() -> userProfileService.updateCurrentProfile(
                UserProfileUpdateRequest.builder().build()))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_REQUEST));
        verify(authenticatedUserResolver, never()).getCurrentUser();
    }

    private User user(RoleCode roleCode) {
        User user = User.builder()
                .email("user@example.com")
                .fullName("User Name")
                .role(Role.builder().code(roleCode).build())
                .build();
        user.setId(UUID.randomUUID().toString());
        return user;
    }
}
