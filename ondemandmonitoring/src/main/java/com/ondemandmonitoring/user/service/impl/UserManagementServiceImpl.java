package com.ondemandmonitoring.user.service.impl;

import com.ondemandmonitoring.common.api.PageResponse;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.auth.service.IAuthAccountSynchronizationService;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.domain.CustomerProfile;
import com.ondemandmonitoring.user.domain.UserIdentity;
import com.ondemandmonitoring.user.dto.response.UserManagementDetailResponse;
import com.ondemandmonitoring.user.dto.response.UserManagementSummaryResponse;
import com.ondemandmonitoring.user.mapper.UserManagementMapper;
import com.ondemandmonitoring.user.repository.CustomerProfileRepository;
import com.ondemandmonitoring.user.repository.UserIdentityRepository;
import com.ondemandmonitoring.user.repository.UserRepository;
import com.ondemandmonitoring.user.repository.UserManagementSpecifications;
import com.ondemandmonitoring.user.service.IUserManagementService;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.List;
import java.util.Set;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class UserManagementServiceImpl implements IUserManagementService {

    static int MAX_PAGE_SIZE = 100;
    static Set<String> ALLOWED_SORT_PROPERTIES = Set.of(
            "id",
            "fullName",
            "email",
            "role.code",
            "isActive",
            "emailVerified",
            "createdAt",
            "lastLoginAt");

    UserRepository userRepository;
    UserIdentityRepository userIdentityRepository;
    CustomerProfileRepository customerProfileRepository;
    UserManagementMapper userManagementMapper;
    AuthenticatedUserResolver authenticatedUserResolver;
    IAuthAccountSynchronizationService authAccountSynchronizationService;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<UserManagementSummaryResponse> getUsers(
            Pageable pageable,
            String search,
            RoleCode role,
            Boolean active,
            Boolean emailVerified) {
        validatePageable(pageable);
        Sort sort = pageable.getSort();
        if (sort.getOrderFor("id") == null) {
            sort = sort.and(Sort.by("id"));
        }
        Pageable stablePageable = PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                sort);
        Page<User> users = userRepository.findAll(
                UserManagementSpecifications.filter(search, role, active, emailVerified),
                stablePageable);
        return PageResponse.from(users.map(userManagementMapper::toSummary));
    }

    @Override
    @Transactional(readOnly = true)
    public UserManagementDetailResponse getUser(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
        return toDetail(user);
    }

    @Override
    @Transactional
    public UserManagementDetailResponse updateStatus(String userId, boolean active) {
        User actor = authenticatedUserResolver.getCurrentUser();
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
        if (!active && actor.getId().equals(target.getId())) {
            throw new ApiException(ErrorCode.SELF_DEACTIVATION_NOT_ALLOWED);
        }
        if (Boolean.TRUE.equals(target.getIsActive()) == active) {
            return toDetail(target);
        }

        target.setIsActive(active);
        userRepository.save(target);
        List<UserIdentity> identities = userIdentityRepository.findAllByUserId(userId);
        identities.stream()
                .map(UserIdentity::getCognitoUsername)
                .filter(username -> username != null && !username.isBlank())
                .distinct()
                .forEach(username -> authAccountSynchronizationService
                        .scheduleAccountStatusSync(username, active));
        return toDetail(target, identities);
    }

    private UserManagementDetailResponse toDetail(User user) {
        return toDetail(user, userIdentityRepository.findAllByUserId(user.getId()));
    }

    private UserManagementDetailResponse toDetail(User user, List<UserIdentity> identities) {
        CustomerProfile customerProfile = null;
        if (user.getRole().getCode() == RoleCode.CUSTOMER) {
            customerProfile = customerProfileRepository.findById(user.getId()).orElse(null);
        }
        return userManagementMapper.toDetail(
                user,
                identities,
                customerProfile);
    }

    private void validatePageable(Pageable pageable) {
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "Page size must not exceed " + MAX_PAGE_SIZE);
        }
        pageable.getSort().forEach(order -> {
            if (!ALLOWED_SORT_PROPERTIES.contains(order.getProperty())) {
                throw new ApiException(
                        ErrorCode.INVALID_REQUEST,
                        "Unsupported user sort property: " + order.getProperty());
            }
        });
    }
}
