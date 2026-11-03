package com.ondemandmonitoring.delivery.service;

import com.ondemandmonitoring.delivery.dto.request.NotifyOutOfScopeRequest;
import com.ondemandmonitoring.delivery.dto.request.ResultRevisionDecisionRequest;
import com.ondemandmonitoring.delivery.dto.response.ResultRevisionCaseResponse;

public interface IResultRevisionCaseService {
    ResultRevisionCaseResponse getByOrderId(String orderId);
    ResultRevisionCaseResponse decide(String caseId, String caseItemId, ResultRevisionDecisionRequest request);
    ResultRevisionCaseResponse notifyOutOfScope(String caseId, String caseItemId, NotifyOutOfScopeRequest request);
    ResultRevisionCaseResponse acknowledgeOutOfScope(String orderId, String caseItemId);
    com.ondemandmonitoring.delivery.dto.response.ResultRevisionReworkMissionResponse createReworkMission(
            String caseId, com.ondemandmonitoring.mission.dto.request.CreateResultRevisionReworkMissionRequest request);
}
