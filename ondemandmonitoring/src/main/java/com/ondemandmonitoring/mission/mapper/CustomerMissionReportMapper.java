package com.ondemandmonitoring.mission.mapper;

import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.MissionResult;
import com.ondemandmonitoring.mission.domain.MissionChecklistExecution;
import com.ondemandmonitoring.mission.dto.response.*;
import org.mapstruct.*;

@Mapper(componentModel = "spring")
public interface CustomerMissionReportMapper {
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "missionId", source = "mission.id")
    @Mapping(target = "orderId", source = "mission.order.id")
    @Mapping(target = "missionCode", source = "mission.missionCode")
    @Mapping(target = "scopeLabel", source = "mission.scopeLabel")
    @Mapping(target = "status", source = "mission.status")
    @Mapping(target = "scheduledStartAt", source = "mission.scheduledStartAt")
    @Mapping(target = "scheduledEndAt", source = "mission.scheduledEndAt")
    @Mapping(target = "replacesMissionId", source = "visibleReplacementId")
    @Mapping(target = "reportAvailable", source = "available")
    CustomerMissionSummary summary(Mission mission, String visibleReplacementId, boolean available);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "id", source = "id")
    @Mapping(target = "summary", source = "summary")
    @Mapping(target = "startedAt", source = "startedAt")
    @Mapping(target = "endedAt", source = "endedAt")
    @Mapping(target = "completedAt", source = "completedAt")
    @Mapping(target = "durationSeconds", source = "durationSeconds")
    CustomerPublishedMissionResult result(MissionResult result);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "executionId", source = "id")
    @Mapping(target = "orderChecklistItemId", source = "orderChecklistItemId")
    @Mapping(target = "content", source = "orderChecklistItem.content")
    @Mapping(target = "sourceType", source = "orderChecklistItem.sourceType")
    @Mapping(target = "displayOrder", source = "missionChecklistAssignment.displayOrder")
    @Mapping(target = "status", source = "executionStatus")
    @Mapping(target = "assessment", source = "assessmentStatus")
    @Mapping(target = "observation", source = "observation")
    @Mapping(target = "unableToVerifyReason", source = "unableToVerifyReason")
    CustomerChecklistReportItem checklist(MissionChecklistExecution execution);
}
