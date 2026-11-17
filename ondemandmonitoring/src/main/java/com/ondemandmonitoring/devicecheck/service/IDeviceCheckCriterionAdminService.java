package com.ondemandmonitoring.devicecheck.service;

import com.ondemandmonitoring.devicecheck.dto.request.DeviceCheckCriterionReorderRequest;
import com.ondemandmonitoring.devicecheck.dto.request.DeviceCheckCriterionRequest;
import com.ondemandmonitoring.devicecheck.dto.response.DeviceCheckCriterionResponse;
import com.ondemandmonitoring.devicecheck.enums.DeviceCheckPhase;
import java.util.List;

public interface IDeviceCheckCriterionAdminService {
    List<DeviceCheckCriterionResponse> list(DeviceCheckPhase phase);

    DeviceCheckCriterionResponse get(String id);

    DeviceCheckCriterionResponse create(DeviceCheckCriterionRequest request);

    DeviceCheckCriterionResponse update(String id, DeviceCheckCriterionRequest request);

    void delete(String id);

    List<DeviceCheckCriterionResponse> reorder(DeviceCheckCriterionReorderRequest request);
}
