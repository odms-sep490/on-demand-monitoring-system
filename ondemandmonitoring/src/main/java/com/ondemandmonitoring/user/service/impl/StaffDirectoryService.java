package com.ondemandmonitoring.user.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.dto.response.AvailableOperatorResponse;
import com.ondemandmonitoring.user.repository.UserRepository;
import com.ondemandmonitoring.user.service.IStaffDirectoryService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StaffDirectoryService implements IStaffDirectoryService {
    private final UserRepository users;

    @Override
    public User requireActiveStaff(String id) {
        User user = users.findById(id).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
        if (!Boolean.TRUE.equals(user.getIsActive()) || user.getRole() == null || !user.getRole().isActive()
                || user.getRole().getCode() != RoleCode.STAFF) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Assignment requires an active STAFF account");
        }
        return user;
    }

    @Override
    public List<AvailableOperatorResponse> listActiveStaff() {
        return users.findAllByRole_CodeAndIsActiveTrueOrderByFullNameAsc(RoleCode.STAFF).stream()
                .filter(user -> user.getRole().isActive())
                .map(user -> AvailableOperatorResponse.builder().id(user.getId()).fullName(user.getFullName())
                        .email(user.getEmail()).build()).toList();
    }
}
