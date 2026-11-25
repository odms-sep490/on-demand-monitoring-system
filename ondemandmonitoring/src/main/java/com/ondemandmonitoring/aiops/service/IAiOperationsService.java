package com.ondemandmonitoring.aiops.service;

import com.ondemandmonitoring.aiops.dto.AiOperationsDtos.AiEvaluationView;
import com.ondemandmonitoring.aiops.dto.AiOperationsDtos.AiIncidentView;
import com.ondemandmonitoring.aiops.dto.AiOperationsDtos.AiModelView;
import com.ondemandmonitoring.aiops.dto.AiOperationsDtos.AiOverviewView;
import com.ondemandmonitoring.aiops.dto.AiOperationsDtos.AiSecurityOverviewView;
import com.ondemandmonitoring.aiops.dto.AiOperationsDtos.AiTraceView;
import com.ondemandmonitoring.aiops.dto.AiOperationsDtos.CreateIncidentRequest;
import com.ondemandmonitoring.aiops.dto.AiOperationsDtos.EvaluationCaseView;
import com.ondemandmonitoring.aiops.dto.AiOperationsDtos.EvaluationJobView;
import com.ondemandmonitoring.aiops.dto.AiOperationsDtos.ServiceHealthView;
import com.ondemandmonitoring.aiops.dto.AiOperationsDtos.StartEvaluationJobRequest;
import com.ondemandmonitoring.aiops.dto.AiOperationsDtos.SystemResourceView;
import com.ondemandmonitoring.aiops.dto.AiOperationsDtos.TraceSpan;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface IAiOperationsService {

    void ensureSchema();

    AiOverviewView getOverview();

    List<AiModelView> listModels();

    AiModelView toggleModel(String code, boolean enabled);

    AiModelView updateModelConfig(String code, Map<String, Object> config);

    ServiceHealthView probeModel(String code);

    List<ServiceHealthView> checkServices();

    SystemResourceView readSystemResources();

    List<AiTraceView> listTraces(int limit);

    List<AiEvaluationView> listEvaluations();

    List<AiEvaluationView> syncDeepEvalFromDisk();

    List<EvaluationCaseView> listEvaluationCases(
            String metric,
            String status,
            String difficulty,
            String language,
            String search,
            int limit
    );

    List<Map<String, Object>> listEvaluationHistory(int limit);

    List<EvaluationJobView> listEvaluationJobs();

    EvaluationJobView startEvaluationJob(StartEvaluationJobRequest request);

    EvaluationJobView cancelEvaluationJob(UUID jobId);

    AiSecurityOverviewView getSecurityOverview();

    List<AiIncidentView> listIncidents(String statusFilter);

    AiIncidentView createIncident(CreateIncidentRequest request);

    AiIncidentView resolveIncident(UUID id);

    void recordAudit(
            String modelCode,
            String feature,
            String resourceId,
            int durationMs,
            int httpStatus,
            String validationStatus,
            String securityDecision,
            boolean fallbackUsed,
            List<TraceSpan> spans
    );
}
