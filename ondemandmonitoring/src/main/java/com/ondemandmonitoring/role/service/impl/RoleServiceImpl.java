package com.ondemandmonitoring.role.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.role.repository.RoleRepository;
import com.ondemandmonitoring.role.service.RoleService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class RoleServiceImpl implements RoleService {

    RoleRepository roleRepository;

    @Override
    public Role getActiveRole(RoleCode code) {
        return roleRepository.findByCodeAndActiveTrue(code)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.INVALID_REQUEST, "Role is not configured or inactive: " + code));
    }
}
