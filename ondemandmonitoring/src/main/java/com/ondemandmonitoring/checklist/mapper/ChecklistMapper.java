package com.ondemandmonitoring.checklist.mapper;

import com.ondemandmonitoring.checklist.domain.ChecklistDefinition;
import com.ondemandmonitoring.checklist.dto.request.ChecklistRequest;
import com.ondemandmonitoring.checklist.dto.response.ChecklistResponse;
import org.mapstruct.*;

@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface ChecklistMapper {
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "normalizedContent", ignore = true)
    @Mapping(target = "seedCode", ignore = true)
    @Mapping(target = "isActive", constant = "true")
    ChecklistDefinition toEntity(ChecklistRequest request);
    ChecklistResponse toResponse(ChecklistDefinition entity);
}
