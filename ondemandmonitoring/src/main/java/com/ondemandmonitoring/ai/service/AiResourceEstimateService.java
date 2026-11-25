package com.ondemandmonitoring.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.ai.client.OpenRouterChatClient;
import com.ondemandmonitoring.ai.dto.AiResourceEstimateContent;
import com.ondemandmonitoring.ai.dto.AiResourceEstimateResponse;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.prompt.PromptManagementService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiResourceEstimateService {
    private static final int MAX_SUGGESTED_DEVICES = 100;
    private static final String PROMPT_KEY = "mission.resource-estimate.system";
    private static final String BOOTSTRAP_FALLBACK_PROMPT = "You estimate the number of devices needed for a mission. "
            + "Return exactly one JSON object with these fields: minimumSuggestedDevices (positive integer), "
            + "recommendedDevices (integer >= minimumSuggestedDevices), reason (short string), "
            + "assumptions (array of strings), warnings (array of strings). "
            + "Keep reason to at most two short sentences and each array to at most three concise items. "
            + "Do not select dates or resource IDs, check availability, or make assignments. Do not use markdown.";

    private final AiResourceEstimateContextService contextService;
    private final OpenRouterChatClient openRouter;
    private final ObjectMapper objectMapper;
    @Autowired(required = false)
    private PromptManagementService promptManagement;

    public AiResourceEstimateResponse estimate(String missionId) {
        long contextStartedAt = System.nanoTime();
        AiResourceEstimateContextService.EstimateContext context = contextService.buildContext(missionId);
        log.info("Mission AI estimation context loaded; missionId={}, elapsedMs={}",
                missionId, (System.nanoTime() - contextStartedAt) / 1_000_000);
        String systemPrompt = BOOTSTRAP_FALLBACK_PROMPT;
        if (promptManagement != null) {
            try {
                var resolved = promptManagement.resolve(PROMPT_KEY, Map.of());
                systemPrompt = resolved.renderedContent();
                log.info("Resolved dynamic prompt key={} version=v{} staleCache={}",
                        PROMPT_KEY, resolved.versionNumber(), resolved.staleCacheUsed());
            } catch (Exception ex) {
                log.warn("Dynamic prompt resolution failed for key={}, using bootstrap fallback: {}",
                        PROMPT_KEY, ex.getMessage());
            }
        }
        OpenRouterChatClient.Completion completion = openRouter.complete(systemPrompt, context.userPrompt());
        if ("length".equalsIgnoreCase(completion.finishReason())) {
            log.warn("OpenRouter response was truncated; model={}, outputChars={}",
                    completion.model(), completion.content().length());
            throw new ApiException(ErrorCode.AI_RESPONSE_INVALID,
                    "OpenRouter response was truncated before it could be normalized");
        }
        AiResourceEstimateContent content;
        try {
            content = objectMapper.readValue(extractJson(completion.content()), AiResourceEstimateContent.class);
        } catch (JsonProcessingException exception) {
            log.warn("OpenRouter response could not be normalized; model={}, finishReason={}, outputChars={}, cause={}",
                    completion.model(), completion.finishReason(), completion.content().length(),
                    exception.getOriginalMessage());
            throw new ApiException(ErrorCode.AI_RESPONSE_INVALID,
                    "OpenRouter returned a response that could not be normalized");
        }
        if (content == null) {
            throw new ApiException(ErrorCode.AI_RESPONSE_INVALID,
                    "OpenRouter returned an empty structured response");
        }
        Integer recommendedDevices = content.recommendedDevices() != null
                ? content.recommendedDevices() : content.estimatedDeviceCount();
        Integer minimumDevices = content.minimumSuggestedDevices() != null
                ? content.minimumSuggestedDevices() : recommendedDevices == null ? null : 1;
        if (minimumDevices == null || recommendedDevices == null
                || minimumDevices < 1 || recommendedDevices < minimumDevices
                || recommendedDevices > MAX_SUGGESTED_DEVICES) {
            throw new ApiException(ErrorCode.AI_RESPONSE_INVALID,
                    "OpenRouter returned invalid recommended device counts");
        }
        String reason = content.reason() == null || content.reason().isBlank()
                ? content.summary() : content.reason();
        return new AiResourceEstimateResponse(
                context.orderId(),
                missionId,
                completion.model(),
                Instant.now(),
                content.estimatedStaffCount(),
                content.staffByRole().stream()
                        .map(item -> new AiResourceEstimateResponse.StaffRoleEstimate(
                                item.role(), item.count(), item.reason()))
                        .toList(),
                recommendedDevices,
                content.devicesByType().stream()
                        .map(item -> new AiResourceEstimateResponse.DeviceTypeEstimate(
                                item.deviceTypeCode(), item.count(), item.reason()))
                        .toList(),
                reason,
                List.copyOf(content.assumptions()),
                minimumDevices,
                recommendedDevices,
                reason,
                List.copyOf(content.warnings()));
    }

    private String extractJson(String response) {
        String text = response == null ? "" : response.strip();
        if (text.startsWith("```")) {
            int firstLineEnd = text.indexOf('\n');
            if (firstLineEnd >= 0) text = text.substring(firstLineEnd + 1).strip();
            if (text.endsWith("```")) text = text.substring(0, text.length() - 3).strip();
        }
        int objectStart = text.indexOf('{');
        int objectEnd = text.lastIndexOf('}');
        return objectStart >= 0 && objectEnd >= objectStart
                ? text.substring(objectStart, objectEnd + 1)
                : text;
    }
}
