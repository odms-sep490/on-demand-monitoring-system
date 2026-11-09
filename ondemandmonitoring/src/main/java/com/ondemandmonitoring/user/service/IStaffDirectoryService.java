package com.ondemandmonitoring.user.service;

import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.dto.response.AvailableOperatorResponse;
import java.util.List;

public interface IStaffDirectoryService {
    User requireActiveStaff(String id);
    List<AvailableOperatorResponse> listActiveStaff();
}
