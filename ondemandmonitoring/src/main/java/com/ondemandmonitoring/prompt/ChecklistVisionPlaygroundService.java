package com.ondemandmonitoring.prompt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.ai.client.ChecklistImageVisionClient;
import com.ondemandmonitoring.ai.client.ChecklistImageVisionClient.CriterionInput;
import com.ondemandmonitoring.ai.client.ChecklistImageVisionClient.Verdict;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ChecklistVisionPlaygroundService {
    static final String PROMPT_KEY = "checklist.image-vision.system";
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ondemandmonitoring.aiops.service.ChecklistVisionRuntime runtime;
    private final java.util.Set<String> activeActors = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.Semaphore permits = new java.util.concurrent.Semaphore(4);
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ondemandmonitoring.user.service.AuthenticatedUserResolver user;
    private final PromptManagementService prompts;
    private final ChecklistImageVisionClient client;
    private final ObjectMapper json;

    public record Result(List<Finding> findings) {}
    public record Finding(String code, Verdict verdict, String observation, String label) {}

    public Result assess(MultipartFile image, UUID version, String criteria, String language) throws IOException {
        var existing = runtime == null ? null : runtime.currentRequest();
        var scope = existing != null ? existing : runtime == null ? null
                : runtime.openRequest(com.ondemandmonitoring.aiops.service.ChecklistVisionRuntime.httpContext(), true);
        boolean owner = existing == null;
        String actor = scope == null ? "standalone" : scope.traceId();
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof org.springframework.security.oauth2.jwt.Jwt jwt)
            actor = jwt.getSubject() == null ? actor : jwt.getSubject();
        boolean acquired = false;
        boolean actorAcquired = false;
        try {
            if (scope != null) {
                String actorId = user == null ? (authentication != null && authentication.getPrincipal() instanceof org.springframework.security.oauth2.jwt.Jwt jwt ? jwt.getSubject() : null) : user.getCurrentUserId();
                scope.authorized(actorId, null, null, null);
                scope.version(version);
                if (actorId != null) actor = actorId;
            }
            if (image == null || !MediaType.IMAGE_JPEG_VALUE.equalsIgnoreCase(image.getContentType()))
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Expected image/jpeg");
            if (version == null || image.isEmpty() || image.getSize() > ChecklistImageVisionClient.MAX_JPEG_BYTES)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid JPEG size or prompt version");
            var inputs = parseCriteria(criteria);
            if (!activeActors.add(actor)) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
            actorAcquired = true;
            if (!permits.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
            acquired = true;
            String prompt = prompts.previewVersion(PROMPT_KEY, version, Map.of()).renderedPrompt();
            byte[] jpeg;
            try (var input = image.getInputStream()) {
                jpeg = input.readNBytes(ChecklistImageVisionClient.MAX_JPEG_BYTES + 1);
            }
            var findings = client.assessWithPrompt(jpeg, language, inputs, prompt);
            if (scope != null) scope.succeeded(findings.values().stream().anyMatch(f -> f.verdict() == Verdict.MISMATCH)
                    ? "MISMATCH" : findings.values().stream().allMatch(f -> f.verdict() == Verdict.MATCH) ? "MATCH" : "UNCERTAIN");
            return new Result(inputs.stream().map(input -> {
                var finding = findings.get(input.code());
                return new Finding(input.code(), finding.verdict(), finding.observation(), finding.label());
            }).toList());
        } catch (IOException | RuntimeException failure) {
            if (scope != null) scope.failed(failure);
            throw failure;
        } finally {
            if (acquired) permits.release();
            if (actorAcquired) activeActors.remove(actor);
            if (owner && scope != null) scope.close();
        }
    }

    private List<CriterionInput> parseCriteria(String raw) {
        if (raw == null || raw.length() > 16000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid criteria JSON");
        JsonNode array;
        try {
            array = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid criteria JSON");
        }
        if (array == null || !array.isArray() || array.isEmpty() || array.size() > ChecklistImageVisionClient.MAX_CRITERIA) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid criteria array");
        }
        List<CriterionInput> inputs = new ArrayList<>();
        for (var item : array) {
            if (!item.isTextual() || item.textValue().isBlank() || item.textValue().length() > 300
                    || item.textValue().chars().anyMatch(Character::isISOControl)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected nonblank criterion text");
            }
            String instruction = item.textValue().strip();
            inputs.add(new CriterionInput("PHOTO_" + (inputs.size() + 1), instruction, instruction));
        }
        return inputs;
    }
}
