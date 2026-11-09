package com.ondemandmonitoring.mission.mapper;

import com.ondemandmonitoring.mission.domain.MissionStaffAssignment;
import com.ondemandmonitoring.mission.dto.response.MissionStaffAssignmentResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface MissionStaffAssignmentMapper {

    @Mapping(target = "staffId", source = "staff.id")
    @Mapping(target = "staffName", source = "staff.fullName")
    @Mapping(target = "staffEmail", source = "staff.email")
    @Mapping(target = "staffPhone", source = "staff.phoneNumber")
    MissionStaffAssignmentResponse toResponse(MissionStaffAssignment assignment);
}
