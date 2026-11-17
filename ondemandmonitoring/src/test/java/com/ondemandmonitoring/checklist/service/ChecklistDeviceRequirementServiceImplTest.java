package com.ondemandmonitoring.checklist.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.checklist.domain.ChecklistDefinition;
import com.ondemandmonitoring.checklist.domain.ChecklistDeviceRequirement;
import com.ondemandmonitoring.checklist.dto.response.ChecklistDeviceRequirementResponse;
import com.ondemandmonitoring.checklist.mapper.ChecklistDeviceRequirementMapper;
import com.ondemandmonitoring.checklist.repository.ChecklistDefinitionRepository;
import com.ondemandmonitoring.checklist.repository.ChecklistDeviceRequirementRepository;
import com.ondemandmonitoring.checklist.service.impl.ChecklistDeviceRequirementServiceImpl;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.device.domain.DeviceType;
import com.ondemandmonitoring.device.dto.response.DeviceTypeResponse;
import com.ondemandmonitoring.device.repository.DeviceTypeRepository;
import com.ondemandmonitoring.device.mapper.DeviceTypeMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChecklistDeviceRequirementServiceImplTest {
    ChecklistDefinitionRepository checklists;
    DeviceTypeRepository deviceTypes;
    ChecklistDeviceRequirementRepository requirements;
    ChecklistDeviceRequirementMapper mapper;
    DeviceTypeMapper deviceTypeMapper;
    ChecklistDeviceRequirementServiceImpl service;
    ChecklistDefinition checklist;
    DeviceType deviceType;

    @BeforeEach
    void setup() {
        checklists = mock(ChecklistDefinitionRepository.class);
        deviceTypes = mock(DeviceTypeRepository.class);
        requirements = mock(ChecklistDeviceRequirementRepository.class);
        mapper = mock(ChecklistDeviceRequirementMapper.class);
        deviceTypeMapper = mock(DeviceTypeMapper.class);
        service = new ChecklistDeviceRequirementServiceImpl(
                checklists, deviceTypes, requirements, mapper, deviceTypeMapper);
        checklist = new ChecklistDefinition();
        checklist.setId("c1");
        deviceType = new DeviceType();
        deviceType.setId("t1");
        when(checklists.findById("c1")).thenReturn(Optional.of(checklist));
        when(deviceTypes.findById("t1")).thenReturn(Optional.of(deviceType));
        when(requirements.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(mapper.toResponse(any())).thenReturn(new ChecklistDeviceRequirementResponse());
    }

    @Test
    void addsOneDeviceTypeRelation() {
        service.add("c1", "t1");
        verify(requirements).saveAndFlush(any(ChecklistDeviceRequirement.class));
    }

    @Test
    void duplicateRelationIsConflict() {
        when(requirements.existsByChecklistDefinitionIdAndDeviceTypeId("c1", "t1")).thenReturn(true);
        assertEquals(ErrorCode.CHECKLIST_DEVICE_TYPE_ALREADY_EXISTS,
                assertThrows(ApiException.class, () -> service.add("c1", "t1")).getErrorCode());
        verify(requirements, never()).saveAndFlush(any());
    }

    @Test
    void removesByChecklistAndDeviceTypeIds() {
        var requirement = new ChecklistDeviceRequirement();
        when(checklists.existsById("c1")).thenReturn(true);
        when(requirements.findByChecklistDefinitionIdAndDeviceTypeId("c1", "t1"))
                .thenReturn(Optional.of(requirement));
        service.remove("c1", "t1");
        verify(requirements).delete(requirement);
    }

    @Test
    void groupsDeviceTypesForExistingChecklistResponses() {
        var requirement = new ChecklistDeviceRequirement();
        requirement.setChecklistDefinition(checklist);
        requirement.setDeviceType(deviceType);
        var response = new DeviceTypeResponse();
        response.setId("t1");
        when(requirements.findAllByChecklistIds(List.of("c1"))).thenReturn(List.of(requirement));
        when(deviceTypeMapper.toResponse(deviceType)).thenReturn(response);
        assertEquals(List.of("t1"), service.findDeviceTypesByChecklistIds(List.of("c1"))
                .get("c1").stream().map(DeviceTypeResponse::getId).toList());
    }
}
