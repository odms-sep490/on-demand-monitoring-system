package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.mission.dto.request.ExecutionPlanFinalizeRequest;
import com.ondemandmonitoring.mission.dto.response.ExecutionPlanResponse;

public interface IExecutionPlanService {
    ExecutionPlanResponse get(String orderId);
    ExecutionPlanResponse finalizePlan(String orderId, ExecutionPlanFinalizeRequest request);

}
