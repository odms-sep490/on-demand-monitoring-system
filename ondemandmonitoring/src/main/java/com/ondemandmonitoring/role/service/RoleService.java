package com.ondemandmonitoring.role.service;

import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;

public interface RoleService {

    Role getActiveRole(RoleCode code);
}
