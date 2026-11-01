package com.ondemandmonitoring.mission.mapper;

import com.ondemandmonitoring.mission.domain.MissionChecklistExecution;
import com.ondemandmonitoring.mission.dto.response.ChecklistExecutionResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.AfterMapping;
import org.mapstruct.MappingTarget;
import com.ondemandmonitoring.order.enums.OrderChecklistSourceType;

@Mapper(componentModel = "spring")
public interface MissionChecklistExecutionMapper {
    @Mapping(target = "content", source = "orderChecklistItem.content")
    @Mapping(target = "description", source = "orderChecklistItem.descriptionSnapshot")
    @Mapping(target = "executionSteps", source = "orderChecklistItem.executionStepsSnapshot")
    @Mapping(target = "inputRequirements", source = "orderChecklistItem.inputRequirementsSnapshot")
    @Mapping(target = "expectedOutputs", source = "orderChecklistItem.expectedOutputsSnapshot")
    @Mapping(target = "displayOrder", source = "missionChecklistAssignment.displayOrder")
    @Mapping(target = "sourceType", source = "orderChecklistItem.sourceType")
    @Mapping(target = "evidencePolicyVersion", source = "orderChecklistItem.evidencePolicyVersion")
    @Mapping(target = "minimumEvidenceCount", source = "orderChecklistItem.minimumEvidenceCount")
    @Mapping(target = "requiredEvidenceType", source = "orderChecklistItem.requiredEvidenceType")
    @Mapping(target = "minimumImageCount", source = "orderChecklistItem.minimumImageCount")
    @Mapping(target = "minimumVideoCount", source = "orderChecklistItem.minimumVideoCount")
    ChecklistExecutionResponse toResponse(MissionChecklistExecution execution);

    /** The service prepares the accepted snapshot once; mapping never queries finance. */
    default ChecklistExecutionResponse toResponse(MissionChecklistExecution execution, MissionChecklistGuidanceContext guidance) {
        if (execution == null) return null;
        var accepted = guidance.resolve(execution.getOrderChecklistItem());
        var response = toResponse(execution);
        if (accepted != null) {
            response.setDescription(accepted.description());
            response.setExecutionSteps(accepted.executionSteps());
            response.setInputRequirements(accepted.inputRequirements());
            response.setExpectedOutputs(accepted.expectedOutputs());
        }
        return response;
    }

    @AfterMapping
    default void blankCustomGuidance(MissionChecklistExecution execution, @MappingTarget ChecklistExecutionResponse response) {
        if (execution.getOrderChecklistItem() != null
                && execution.getOrderChecklistItem().getSourceType() == OrderChecklistSourceType.CUSTOMER_CUSTOM) {
            response.setDescription(null);
            response.setExecutionSteps(null);
            response.setInputRequirements(null);
            response.setExpectedOutputs(null);
        }
    }
}
