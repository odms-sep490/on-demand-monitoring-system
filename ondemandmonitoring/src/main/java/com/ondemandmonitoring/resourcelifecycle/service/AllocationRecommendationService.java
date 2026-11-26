package com.ondemandmonitoring.resourcelifecycle.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.repository.DeviceRepository;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.resourcelifecycle.AllocationScoringEngine;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.DroneHealthResponse;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.EligibilityResponse;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.RecommendationResponse;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.StaffWorkloadResponse;
import com.ondemandmonitoring.resourcelifecycle.entity.ResourceAllocationEvaluationEntity;
import com.ondemandmonitoring.resourcelifecycle.entity.ResourcePolicyEntity;
import com.ondemandmonitoring.resourcelifecycle.repository.ResourceAllocationEvaluationRepository;
import com.ondemandmonitoring.resourcelifecycle.repository.ResourcePolicyRepository;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.repository.UserRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs deterministic hard-constraint filtering followed by weighted scoring.
 * Optional local Ollama explanation is strictly read-only and degrades gracefully when offline.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AllocationRecommendationService {

    public static final String COMPONENT_TYPE = "RULE_ENGINE";

    private final MissionRepository missionRepository;
    private final DeviceRepository deviceRepository;
    private final UserRepository userRepository;
    private final ResourcePolicyRepository policyRepository;
    private final ResourceAllocationEvaluationRepository evaluationRepository;
    private final ResourceEligibilityService eligibilityService;
    private final DroneHealthService droneHealthService;
    private final WorkforceWorkloadService workforceWorkloadService;
    private final LifecycleJsonHelper jsonHelper;
    private final AuthenticatedUserResolver userResolver;
    private final JdbcTemplate jdbcTemplate;

    private final AllocationScoringEngine scoringEngine = new AllocationScoringEngine();

    @Value("${ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${ollama.explanation-model:qwen2.5:3b}")
    private String ollamaModel;

    @Transactional
    public RecommendationResponse recommendForMission(String missionId) {
        long startNanos = System.nanoTime();
        Mission mission = missionRepository.findById(missionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Mission not found: " + missionId));

        AllocationScoringEngine.Weights weights = resolveActiveWeights();
        List<AllocationScoringEngine.CandidateInput> deviceInputs = buildDeviceCandidates(mission);
        List<AllocationScoringEngine.CandidateInput> staffInputs = buildStaffCandidates(mission);

        List<AllocationScoringEngine.ScoredCandidate> rankedDevices = scoringEngine.scoreAndRank(deviceInputs, weights);
        List<AllocationScoringEngine.ScoredCandidate> rankedStaff = scoringEngine.scoreAndRank(staffInputs, weights);

        String explanation = tryGenerateOllamaExplanation(mission, rankedDevices, rankedStaff);
        boolean explanationAvailable = explanation != null && !explanation.isBlank();
        if (!explanationAvailable) {
            explanation = buildDeterministicSummary(rankedDevices, rankedStaff);
        }

        long elapsedMs = Math.max(1L, Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
        String actorId = userResolver.getCurrentUserId();

        ResourceAllocationEvaluationEntity eval = new ResourceAllocationEvaluationEntity();
        eval.setId(UUID.randomUUID());
        eval.setMissionId(missionId);
        eval.setComponentType(COMPONENT_TYPE);
        eval.setInputSnapshotJson(jsonHelper.toJson(Map.of(
                "missionId", missionId,
                "scheduledStartAt", String.valueOf(mission.getScheduledStartAt()),
                "scheduledEndAt", String.valueOf(mission.getScheduledEndAt()),
                "weights", weights,
                "deviceCandidateCount", deviceInputs.size(),
                "staffCandidateCount", staffInputs.size())));
        eval.setOutputSnapshotJson(jsonHelper.toJson(Map.of(
                "devices", rankedDevices,
                "staff", rankedStaff,
                "aiExplanationAvailable", explanationAvailable)));
        eval.setActorId(actorId);
        eval.setAlgorithmVersion(AllocationScoringEngine.ALGORITHM_VERSION);
        eval.setElapsedMs(elapsedMs);
        eval.setCreatedAt(Instant.now());
        evaluationRepository.save(eval);

        recordAiOpsAuditIfAvailable(eval.getId(), missionId, actorId, elapsedMs, explanationAvailable);

        return new RecommendationResponse(
                eval.getId(),
                missionId,
                AllocationScoringEngine.ALGORITHM_VERSION,
                COMPONENT_TYPE,
                elapsedMs,
                explanation,
                explanationAvailable,
                rankedDevices,
                rankedStaff);
    }

    private List<AllocationScoringEngine.CandidateInput> buildDeviceCandidates(Mission mission) {
        List<AllocationScoringEngine.CandidateInput> inputs = new ArrayList<>();
        for (Device device : deviceRepository.findAll()) {
            EligibilityResponse eligibility = eligibilityService.evaluateDeviceEligibility(
                    device.getId(), mission.getScheduledStartAt(), mission.getScheduledEndAt());
            DroneHealthResponse health = droneHealthService.evaluateDevice(device, null);

            Double remainingBudgetRatio = health.utilizationPercent() == null
                    ? null
                    : Math.max(0.0, 1.0 - (health.utilizationPercent() / 100.0));
            Double dataQuality = "UNKNOWN".equals(health.usageSource())
                    ? null
                    : ("TELEMETRY_ACTUAL".equals(health.usageSource()) || "VERIFIED_MANUAL".equals(health.usageSource()))
                            ? 1.0
                            : 0.5;

            inputs.add(new AllocationScoringEngine.CandidateInput(
                    device.getId(),
                    "DEVICE",
                    device.getName() + " (" + device.getSerialNumber() + ")",
                    eligibility.eligible(),
                    eligibility.blockingReasons(),
                    eligibility.warningReasons(),
                    remainingBudgetRatio,
                    null,
                    null,
                    dataQuality));
        }
        return inputs;
    }

    private List<AllocationScoringEngine.CandidateInput> buildStaffCandidates(Mission mission) {
        List<AllocationScoringEngine.CandidateInput> inputs = new ArrayList<>();
        List<User> staffList = userRepository
                .findAllByRole_CodeAndIsActiveTrueOrderByFullNameAsc(RoleCode.STAFF);
        for (User staff : staffList) {
            EligibilityResponse eligibility = eligibilityService.evaluateStaffEligibility(
                    staff.getId(), mission.getScheduledStartAt(), mission.getScheduledEndAt(), mission.getId());
            StaffWorkloadResponse workload = workforceWorkloadService.evaluateStaff(
                    staff, mission.getScheduledStartAt(), mission.getScheduledEndAt(), mission.getId(), null);

            Double workloadRatio = workload.maxUtilizationPercent() == null
                    ? null
                    : workload.maxUtilizationPercent() / 100.0;
            Double restRatio = workload.restSecondsBeforeNextMission() == null
                    ? null
                    : Math.min(1.0, workload.restSecondsBeforeNextMission() / (12.0 * 3600.0));
            Double dataQuality = (workload.weeklyActualSeconds() > 0L)
                    ? 1.0
                    : (workload.weeklyEstimatedSeconds() > 0L ? 0.5 : null);

            inputs.add(new AllocationScoringEngine.CandidateInput(
                    staff.getId(),
                    "STAFF",
                    staff.getFullName(),
                    eligibility.eligible(),
                    eligibility.blockingReasons(),
                    eligibility.warningReasons(),
                    null,
                    workloadRatio,
                    restRatio,
                    dataQuality));
        }
        return inputs;
    }

    private AllocationScoringEngine.Weights resolveActiveWeights() {
        ResourcePolicyEntity policy = policyRepository
                .findFirstByFamilyAndTargetIdAndActiveTrue("ALLOCATION", "DEFAULT")
                .orElse(null);
        if (policy == null) {
            return AllocationScoringEngine.Weights.defaults();
        }
        Map<String, Object> rules = jsonHelper.toMap(policy.getRulesJson());
        Double healthW = jsonHelper.readDouble(rules, "healthMarginWeight");
        Double workloadW = jsonHelper.readDouble(rules, "workloadBalanceWeight");
        Double restW = jsonHelper.readDouble(rules, "restMarginWeight");
        Double qualityW = jsonHelper.readDouble(rules, "dataQualityWeight");
        if (healthW == null && workloadW == null && restW == null && qualityW == null) {
            return AllocationScoringEngine.Weights.defaults();
        }
        return new AllocationScoringEngine.Weights(
                healthW == null ? 0.40 : healthW,
                workloadW == null ? 0.30 : workloadW,
                restW == null ? 0.15 : restW,
                qualityW == null ? 0.15 : qualityW);
    }

    private String tryGenerateOllamaExplanation(
            Mission mission,
            List<AllocationScoringEngine.ScoredCandidate> devices,
            List<AllocationScoringEngine.ScoredCandidate> staff) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            String prompt = "Tóm tắt ngắn gọn (2 câu tiếng Việt) lý do gợi ý phân bổ cho nhiệm vụ "
                    + mission.getMissionCode()
                    + ". Drone top: " + (devices.isEmpty() ? "không có" : devices.getFirst().displayName())
                    + " (eligible=" + (!devices.isEmpty() && devices.getFirst().eligible()) + ")."
                    + " Nhân sự top: " + (staff.isEmpty() ? "không có" : staff.getFirst().displayName())
                    + " (eligible=" + (!staff.isEmpty() && staff.getFirst().eligible()) + ").";
            String body = jsonHelper.toJson(Map.of(
                    "model", ollamaModel,
                    "prompt", prompt,
                    "stream", false));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaBaseUrl + "/api/generate"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                Map<String, Object> parsed = jsonHelper.toMap(response.body());
                Object text = parsed.get("response");
                return text == null ? null : text.toString().trim();
            }
        } catch (Exception ex) {
            log.debug("Ollama explanation unavailable; falling back to deterministic summary: {}", ex.getMessage());
        }
        return null;
    }

    private String buildDeterministicSummary(
            List<AllocationScoringEngine.ScoredCandidate> devices,
            List<AllocationScoringEngine.ScoredCandidate> staff) {
        long eligibleDevices = devices.stream().filter(AllocationScoringEngine.ScoredCandidate::eligible).count();
        long eligibleStaff = staff.stream().filter(AllocationScoringEngine.ScoredCandidate::eligible).count();
        return "Đánh giá xác định (" + COMPONENT_TYPE + "): "
                + eligibleDevices + "/" + devices.size() + " drone đủ điều kiện, "
                + eligibleStaff + "/" + staff.size() + " nhân sự đủ điều kiện. "
                + "(LLM giải thích đang ngoại tuyến; kết quả chấm điểm xác định vẫn hoạt động bình thường.)";
    }

    private void recordAiOpsAuditIfAvailable(
            UUID evalId, String missionId, String actorId, long elapsedMs, boolean llmUsed) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO ai_execution_audits (id, trace_id, feature, model_code, user_id, resource_id, "
                            + "duration_ms, http_status, fallback_used, validation_status, security_decision, "
                            + "human_review_status, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, 200, ?, 'PASSED', 'ALLOW', 'PENDING', now())",
                    evalId,
                    "rl-" + evalId,
                    "resource_allocation_rule_engine",
                    COMPONENT_TYPE,
                    actorId,
                    missionId,
                    (int) elapsedMs,
                    !llmUsed);
        } catch (RuntimeException ex) {
            log.debug("Skipped AI Operations audit mirror (table unavailable or schema differs): {}", ex.getMessage());
        }
    }
}
