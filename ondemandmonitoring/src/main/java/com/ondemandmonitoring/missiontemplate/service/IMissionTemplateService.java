package com.ondemandmonitoring.missiontemplate.service;

import com.ondemandmonitoring.missiontemplate.dto.MissionTemplateRequest;
import com.ondemandmonitoring.missiontemplate.dto.MissionTemplateResponse;
import java.util.List;

public interface IMissionTemplateService {
    List<MissionTemplateResponse> getAll(String serviceId);
    MissionTemplateResponse get(String serviceId, String templateId);
    MissionTemplateResponse create(String serviceId, MissionTemplateRequest request);
    MissionTemplateResponse update(String serviceId, String templateId, MissionTemplateRequest request);
    void delete(String serviceId, String templateId);
}
