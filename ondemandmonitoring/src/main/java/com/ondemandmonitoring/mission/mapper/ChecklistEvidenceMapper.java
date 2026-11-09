package com.ondemandmonitoring.mission.mapper;

import com.ondemandmonitoring.mission.domain.MissionChecklistEvidence;
import com.ondemandmonitoring.mission.dto.response.ChecklistEvidenceResponse;
import org.mapstruct.*;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ChecklistEvidenceMapper {
    @Mapping(target = "evidenceId", source = "id")
    @Mapping(target = "executionId", source = "missionChecklistExecution.id")
    @Mapping(target = "mediaId", source = "mediaAsset.id")
    @Mapping(target = "mediaType", source = "mediaAsset.type")
    @Mapping(target = "contentType", source = "mediaAsset.contentType")
    @Mapping(target = "fileName", source = "mediaAsset.originalFileName")
    @Mapping(target = "mediaStatus", source = "mediaAsset.mediaStatus")
    @Mapping(target = "sourceType", source = "mediaAsset.sourceType")
    @Mapping(target = "capturedAt", source = "mediaAsset.capturedAt")
    @Mapping(target = "sourceCapturedAt", source = "mediaAsset.sourceCapturedAt")
    @Mapping(target = "validatedAt", source = "mediaAsset.validatedAt")
    ChecklistEvidenceResponse toResponse(MissionChecklistEvidence entity);
}
