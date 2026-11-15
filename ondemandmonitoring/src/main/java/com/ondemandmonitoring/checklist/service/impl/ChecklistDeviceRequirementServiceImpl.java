package com.ondemandmonitoring.checklist.service.impl;

import com.ondemandmonitoring.checklist.domain.ChecklistDeviceRequirement;
import com.ondemandmonitoring.checklist.dto.response.ChecklistDeviceRequirementResponse;
import com.ondemandmonitoring.checklist.mapper.ChecklistDeviceRequirementMapper;
import com.ondemandmonitoring.checklist.repository.ChecklistDefinitionRepository;
import com.ondemandmonitoring.checklist.repository.ChecklistDeviceRequirementRepository;
import com.ondemandmonitoring.checklist.service.IChecklistDeviceRequirementService;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.device.dto.response.DeviceTypeResponse;
import com.ondemandmonitoring.device.mapper.DeviceTypeMapper;
import com.ondemandmonitoring.device.repository.DeviceTypeRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChecklistDeviceRequirementServiceImpl implements IChecklistDeviceRequirementService {
    private final ChecklistDefinitionRepository checklists;
    private final DeviceTypeRepository deviceTypes;
    private final ChecklistDeviceRequirementRepository requirements;
    private final ChecklistDeviceRequirementMapper mapper;
    private final DeviceTypeMapper deviceTypeMapper;

    @Override
    @Transactional
    public ChecklistDeviceRequirementResponse add(String checklistId, String deviceTypeId) {
        var checklist = checklists.findById(checklistId)
                .orElseThrow(() -> new ApiException(ErrorCode.CHECKLIST_NOT_FOUND));
        var deviceType = deviceTypes.findById(deviceTypeId)
                .orElseThrow(() -> new ApiException(ErrorCode.DEVICE_TYPE_NOT_FOUND));
        if (requirements.existsByChecklistDefinitionIdAndDeviceTypeId(checklistId, deviceTypeId)) {
            throw new ApiException(ErrorCode.CHECKLIST_DEVICE_TYPE_ALREADY_EXISTS);
        }
        var requirement = new ChecklistDeviceRequirement();
        requirement.setChecklistDefinition(checklist);
        requirement.setDeviceType(deviceType);
        try {
            return mapper.toResponse(requirements.saveAndFlush(requirement));
        } catch (DataIntegrityViolationException conflict) {
            throw new ApiException(ErrorCode.CHECKLIST_DEVICE_TYPE_ALREADY_EXISTS);
        }
    }

    @Override
    @Transactional
    public void remove(String checklistId, String deviceTypeId) {
        if (!checklists.existsById(checklistId)) {
            throw new ApiException(ErrorCode.CHECKLIST_NOT_FOUND);
        }
        var requirement = requirements.findByChecklistDefinitionIdAndDeviceTypeId(checklistId, deviceTypeId)
                .orElseThrow(() -> new ApiException(ErrorCode.CHECKLIST_DEVICE_TYPE_NOT_FOUND));
        requirements.delete(requirement);
        requirements.flush();
    }

    @Override
    public Map<String, List<DeviceTypeResponse>> findDeviceTypesByChecklistIds(Collection<String> checklistIds) {
        if (checklistIds == null || checklistIds.isEmpty()) return Map.of();
        Map<String, List<DeviceTypeResponse>> result = new LinkedHashMap<>();
        requirements.findAllByChecklistIds(checklistIds).forEach(requirement ->
                result.computeIfAbsent(requirement.getChecklistDefinition().getId(), ignored -> new java.util.ArrayList<>())
                        .add(deviceTypeMapper.toResponse(requirement.getDeviceType())));
        return result;
    }
}
