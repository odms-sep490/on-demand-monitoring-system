package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.ai.client.ChecklistImageVisionClient;
import com.ondemandmonitoring.ai.service.ChecklistImageAiSettingsService;
import com.ondemandmonitoring.ai.client.ChecklistImageVisionClient.Verdict;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.mission.dto.request.ChecklistImageContentAssessmentRequest;
import com.ondemandmonitoring.mission.dto.response.ChecklistImageContentAssessmentResponse;
import com.ondemandmonitoring.mission.dto.response.ChecklistImageContentAssessmentResponse.Criterion;
import com.ondemandmonitoring.mission.service.ChecklistImageAssessmentTarget;
import com.ondemandmonitoring.mission.service.IChecklistEvidenceService;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;

/** Executes advisory vision requests outside the checklist read transaction. */
@Service
@RequiredArgsConstructor
public class ChecklistImageContentAssessmentService {
    private static final int MAX_CONCURRENT_REQUESTS = 4;
    private static final int MAX_REQUESTS_PER_MINUTE = 48;
    private static final int MAX_REQUESTS_PER_INSPECTOR_PER_MINUTE = 8;
    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);
    private final IChecklistEvidenceService evidence;
    private final IMediaObjectStorage storage;
    private final ChecklistImageVisionClient vision;
    private final AuthenticatedUserResolver user;
    private final Clock clock;
    private final ChecklistImageAiSettingsService settings;
    private final ArrayDeque<RequestStart> recent = new ArrayDeque<>();
    private final Set<String> activeInspectors = new HashSet<>();

    public boolean available() {
        return settings.isEnabled() && vision.available() && vision.runtimeEnabled();
    }

    public ChecklistImageContentAssessmentResponse assess(String missionId, String executionId, String evidenceId,
            ChecklistImageContentAssessmentRequest request) {
        var target = evidence.resolveContentAssessment(missionId, executionId, evidenceId, request);
        if (!settings.isEnabled() || !vision.runtimeEnabled())
            throw new ApiException(ErrorCode.CHECKLIST_IMAGE_ASSESSMENT_DISABLED);
        if (!vision.available())
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Checklist image content assessment is disabled");

        String inspector = user.getCurrentUserId();
        begin(inspector);
        try {
            var findings = vision.assess(readPreview(target), request.language(), target.criteria(), target.context());
            if (!settings.isEnabled() || !vision.runtimeEnabled())
                throw new ApiException(ErrorCode.CHECKLIST_IMAGE_ASSESSMENT_DISABLED);
            var current = evidence.resolveContentAssessment(missionId, executionId, evidenceId, request);
            if (!target.equals(current)) throw new ApiException(ErrorCode.CONCURRENT_UPDATE);
            var criteria = target.criteria().stream().map(criterion -> {
                var finding = findings.get(criterion.code());
                return new Criterion(criterion.code(), criterion.label(), finding.verdict(), finding.observation());
            }).toList();
            return new ChecklistImageContentAssessmentResponse(
                    target.executionVersion(), target.evidenceId(), overallVerdict(criteria), criteria);
        } finally {
            end(inspector);
        }
    }

    private byte[] readPreview(ChecklistImageAssessmentTarget target) {
        int maxBytes = ChecklistImageVisionClient.MAX_JPEG_BYTES;
        try {
            var preview = storage.open(target.bucket(), target.previewKey());
            try (var input = preview.inputStream()) {
                if (!"image/jpeg".equalsIgnoreCase(preview.contentType())
                        || (preview.contentLength() != null && preview.contentLength() > maxBytes))
                    throw new ApiException(ErrorCode.MEDIA_DERIVATIVE_UNAVAILABLE);
                byte[] bytes = input.readNBytes(maxBytes + 1);
                if (bytes.length < 3 || bytes.length > maxBytes
                        || bytes[0] != (byte) 0xff || bytes[1] != (byte) 0xd8 || bytes[2] != (byte) 0xff)
                    throw new ApiException(ErrorCode.MEDIA_DERIVATIVE_UNAVAILABLE);
                return bytes;
            }
        } catch (SdkException | IOException e) {
            throw new ApiException(ErrorCode.MEDIA_STORAGE_UNAVAILABLE);
        }
    }

    private static Verdict overallVerdict(List<Criterion> criteria) {
        if (criteria.stream().anyMatch(criterion -> criterion.verdict() == Verdict.MISMATCH)) return Verdict.MISMATCH;
        return criteria.stream().allMatch(criterion -> criterion.verdict() == Verdict.MATCH)
                ? Verdict.MATCH : Verdict.UNCERTAIN;
    }

    private synchronized void begin(String inspector) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(RATE_WINDOW);
        while (!recent.isEmpty() && !recent.getFirst().at().isAfter(cutoff)) recent.removeFirst();
        if (activeInspectors.size() >= MAX_CONCURRENT_REQUESTS || activeInspectors.contains(inspector)
                || recent.size() >= MAX_REQUESTS_PER_MINUTE
                || recent.stream().filter(start -> start.inspector().equals(inspector)).count()
                    >= MAX_REQUESTS_PER_INSPECTOR_PER_MINUTE)
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Checklist image content assessment rate limit reached");
        recent.addLast(new RequestStart(inspector, now));
        activeInspectors.add(inspector);
    }

    private synchronized void end(String inspector) {
        activeInspectors.remove(inspector);
    }

    private record RequestStart(String inspector, Instant at) {}
}
