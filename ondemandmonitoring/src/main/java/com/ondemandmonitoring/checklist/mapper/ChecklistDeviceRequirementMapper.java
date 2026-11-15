package com.ondemandmonitoring.checklist.mapper;

import com.ondemandmonitoring.checklist.domain.ChecklistDeviceRequirement;
import com.ondemandmonitoring.checklist.dto.response.ChecklistDeviceRequirementResponse;
import com.ondemandmonitoring.device.mapper.DeviceTypeMapper;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", uses = DeviceTypeMapper.class,
        builder = @Builder(disableBuilder = true))
public interface ChecklistDeviceRequirementMapper {

    @Mapping(target = "checklistId", source = "checklistDefinition.id")
    ChecklistDeviceRequirementResponse toResponse(ChecklistDeviceRequirement entity);
}
