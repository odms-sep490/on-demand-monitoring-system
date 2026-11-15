package com.ondemandmonitoring.checklist.service;

import com.ondemandmonitoring.checklist.dto.response.ChecklistDeviceRequirementResponse;
import com.ondemandmonitoring.device.dto.response.DeviceTypeResponse;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface IChecklistDeviceRequirementService {
    ChecklistDeviceRequirementResponse add(String checklistId, String deviceTypeId);
    void remove(String checklistId, String deviceTypeId);
    Map<String, List<DeviceTypeResponse>> findDeviceTypesByChecklistIds(Collection<String> checklistIds);
}
