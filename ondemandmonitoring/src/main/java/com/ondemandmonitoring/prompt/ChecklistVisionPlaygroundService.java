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
    private final PromptManagementService prompts;
    private final ChecklistImageVisionClient client;
    private final ObjectMapper json;

    public record Result(List<Finding> findings) {}
    public record Finding(String code, Verdict verdict, String observation) {}

    public Result assess(MultipartFile image, UUID version, String criteria, String language) throws IOException {
        if (!MediaType.IMAGE_JPEG_VALUE.equalsIgnoreCase(image.getContentType())) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Expected image/jpeg");
        }
        if (image.isEmpty() || image.getSize() > ChecklistImageVisionClient.MAX_JPEG_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid JPEG size");
        }
        var inputs = parseCriteria(criteria);
        String prompt = prompts.previewVersion(PROMPT_KEY, version, Map.of()).renderedPrompt();
        var findings = client.assessWithPrompt(image.getBytes(), language, inputs, prompt);
        return new Result(inputs.stream().map(input -> {
            var finding = findings.get(input.code());
            return new Finding(input.code(), finding.verdict(), finding.observation());
        }).toList());
    }

    private List<CriterionInput> parseCriteria(String raw) {
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
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected nonblank criterion text");
            }
            String instruction = item.textValue().strip();
            inputs.add(new CriterionInput("PHOTO_" + (inputs.size() + 1), instruction, instruction));
        }
        return inputs;
    }
}
