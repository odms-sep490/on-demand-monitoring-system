package com.ondemandmonitoring.user.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.CustomerProfile;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.dto.request.UserProfileUpdateRequest;
import com.ondemandmonitoring.user.dto.response.UserProfileResponse;
import com.ondemandmonitoring.user.mapper.UserProfileMapper;
import com.ondemandmonitoring.user.repository.CustomerProfileRepository;
import com.ondemandmonitoring.user.repository.UserRepository;
import com.ondemandmonitoring.user.repository.UserIdentityRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.ondemandmonitoring.user.service.IUserProfileService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class UserProfileServiceImpl implements IUserProfileService {

    AuthenticatedUserResolver authenticatedUserResolver;
    CustomerProfileRepository customerProfileRepository;
    UserRepository userRepository;
    UserProfileMapper userProfileMapper;
    UserIdentityRepository userIdentityRepository;

    @Override
    @Transactional(readOnly = true)
    public UserProfileResponse getCurrentProfile() {
        User user = authenticatedUserResolver.getCurrentUser();
        return toProfileResponse(user, findCustomerProfile(user));
    }

    @Override
    @Transactional
    public UserProfileResponse updateCurrentProfile(UserProfileUpdateRequest request) {
        validateUpdateRequest(request);

        User user = authenticatedUserResolver.getCurrentUser();
        boolean customer = user.getRole().getCode() == RoleCode.CUSTOMER;
        if (!customer && containsCustomerFields(request)) {
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "Customer profile fields are only available to customers");
        }

        CustomerProfile customerProfile = customer ? findCustomerProfile(user) : null;
        if (request.getFullName() != null) {
            user.setFullName(request.getFullName().trim());
            userRepository.save(user);
        }
        if (customerProfile != null && containsCustomerFields(request)) {
            applyCustomerUpdates(customerProfile, request);
            customerProfileRepository.save(customerProfile);
        }

        return toProfileResponse(user, customerProfile);
    }

    private UserProfileResponse toProfileResponse(User user, CustomerProfile customerProfile) {
        UserProfileResponse response = userProfileMapper.toResponse(user, customerProfile);
        response.setLinkedProviders(userIdentityRepository.findAllByUserId(user.getId()).stream()
                .map(identity -> identity.getProvider()).distinct().sorted().toList());
        return response;
    }

    private void validateUpdateRequest(UserProfileUpdateRequest request) {
        if (request.getFullName() == null
                && request.getPhoneNumber() == null
                && request.getAddress() == null
                && request.getCompanyName() == null
                && request.getCitizenIdentificationNumber() == null
                && request.getCompanyTaxCode() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "At least one profile field is required");
        }
    }

    private boolean containsCustomerFields(UserProfileUpdateRequest request) {
        return request.getPhoneNumber() != null
                || request.getAddress() != null
                || request.getCompanyName() != null
                || request.getCitizenIdentificationNumber() != null
                || request.getCompanyTaxCode() != null;
    }

    private void applyCustomerUpdates(CustomerProfile customerProfile, UserProfileUpdateRequest request) {
        if (request.getPhoneNumber() != null) {
            customerProfile.setPhoneNumber(trimToNull(request.getPhoneNumber()));
        }
        if (request.getAddress() != null) {
            customerProfile.setAddress(trimToNull(request.getAddress()));
        }
        if (request.getCompanyName() != null) {
            customerProfile.setCompanyName(trimToNull(request.getCompanyName()));
        }
        if (request.getCitizenIdentificationNumber() != null) {
            customerProfile.setCitizenIdentificationNumber(
                    trimToNull(request.getCitizenIdentificationNumber()));
        }
        if (request.getCompanyTaxCode() != null) {
            customerProfile.setCompanyTaxCode(trimToNull(request.getCompanyTaxCode()));
        }
    }

    private String trimToNull(String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private CustomerProfile findCustomerProfile(User user) {
        if (user.getRole().getCode() != RoleCode.CUSTOMER) {
            return null;
        }

        return customerProfileRepository.findById(user.getId())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Customer profile not found"));
    }
}
