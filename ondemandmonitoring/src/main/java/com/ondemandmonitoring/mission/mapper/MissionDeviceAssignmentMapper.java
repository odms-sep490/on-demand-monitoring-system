package com.ondemandmonitoring.mission.mapper;

import com.ondemandmonitoring.mission.domain.MissionDeviceAssignment;
import com.ondemandmonitoring.mission.dto.response.MissionDeviceAssignmentResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface MissionDeviceAssignmentMapper {

    @Mapping(target = "deviceId", source = "device.id")
    @Mapping(target = "deviceCode", source = "device.deviceCode")
    @Mapping(target = "deviceName", source = "device.name")
    @Mapping(target = "deviceSerialNumber", source = "device.serialNumber")
    @Mapping(target = "deviceStatus", source = "device.status")
    MissionDeviceAssignmentResponse toResponse(MissionDeviceAssignment assignment);
}
