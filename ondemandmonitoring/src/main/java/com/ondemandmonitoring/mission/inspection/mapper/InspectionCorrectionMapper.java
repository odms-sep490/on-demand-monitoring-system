package com.ondemandmonitoring.mission.inspection.mapper;

import com.ondemandmonitoring.mission.inspection.domain.InspectionCorrectionEvent;
import com.ondemandmonitoring.mission.inspection.domain.InspectionCorrectionItem;
import com.ondemandmonitoring.mission.inspection.dto.response.InspectionCorrectionResponse;
import org.mapstruct.*;

@Mapper(componentModel = "spring")
public interface InspectionCorrectionMapper {
  @Mapping(target = "executionId", source = "execution.id")
  @Mapping(target = "content", source = "contentSnapshot")
  InspectionCorrectionResponse.Item item(InspectionCorrectionItem item);

  InspectionCorrectionResponse.Event event(InspectionCorrectionEvent event);
}
