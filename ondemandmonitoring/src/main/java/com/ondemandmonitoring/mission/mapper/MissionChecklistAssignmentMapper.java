package com.ondemandmonitoring.mission.mapper;

import com.ondemandmonitoring.mission.domain.MissionChecklistAssignment;
import com.ondemandmonitoring.mission.dto.response.ChecklistAssignmentResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface MissionChecklistAssignmentMapper {
    @Mapping(target = "assignmentId", source = "id")
    @Mapping(target = "content", source = "orderChecklistItem.content")
    @Mapping(target = "sourceType", source = "orderChecklistItem.sourceType")
    @Mapping(target = "minimumEvidenceCount", source = "orderChecklistItem.minimumEvidenceCount")
    @Mapping(target = "requiredEvidenceType", source = "orderChecklistItem.requiredEvidenceType")
    @Mapping(target = "active", expression = "java(assignment.getRemovedAt() == null)")
    ChecklistAssignmentResponse toResponse(MissionChecklistAssignment assignment);
}
