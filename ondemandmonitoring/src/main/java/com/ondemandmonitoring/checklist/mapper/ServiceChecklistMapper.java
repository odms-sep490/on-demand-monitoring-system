package com.ondemandmonitoring.checklist.mapper;

import com.ondemandmonitoring.checklist.domain.ServiceChecklist;
import com.ondemandmonitoring.checklist.dto.response.ServiceChecklistResponse;
import org.mapstruct.*;

@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface ServiceChecklistMapper {
    @Mapping(target = "serviceId", source = "service.id")
    @Mapping(target = "serviceName", source = "service.name")
    @Mapping(target = "serviceActive", source = "service.isActive")
    @Mapping(target = "checklistId", source = "checklist.id")
    @Mapping(target = "content", source = "checklist.content")
    @Mapping(target = "description", source = "checklist.description")
    @Mapping(target = "executionSteps", source = "checklist.executionSteps")
    @Mapping(target = "inputRequirements", source = "checklist.inputRequirements")
    @Mapping(target = "expectedOutputs", source = "checklist.expectedOutputs")
    @Mapping(target = "checklistActive", source = "checklist.isActive")
    @Mapping(target = "checklistVersion", source = "checklist.version")
    @Mapping(target = "checklistIncludedImageCount", source = "checklist.includedImageCount")
    @Mapping(target = "checklistIncludedVideoCount", source = "checklist.includedVideoCount")
    @Mapping(target = "standardVideoDurationSeconds", source = "checklist.standardVideoDurationSeconds")
    @Mapping(target = "deviceTypes", ignore = true)
    @Mapping(target = "includedImageCount", expression = "java(entity.getIncludedImageCountOverride() != null ? entity.getIncludedImageCountOverride() : Integer.valueOf(entity.getChecklist().getIncludedImageCount()))")
    @Mapping(target = "includedVideoCount", expression = "java(entity.getIncludedVideoCountOverride() != null ? entity.getIncludedVideoCountOverride() : Integer.valueOf(entity.getChecklist().getIncludedVideoCount()))")
    ServiceChecklistResponse toResponse(ServiceChecklist entity);
}
