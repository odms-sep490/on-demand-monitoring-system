package com.ondemandmonitoring.prompt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.ai.client.ChecklistImageVisionClient;
import com.ondemandmonitoring.ai.client.ChecklistImageVisionClient.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class ChecklistVisionPlaygroundTest {
    private final PromptManagementService prompts = mock(PromptManagementService.class);
    private final ChecklistImageVisionClient client = mock(ChecklistImageVisionClient.class);
    private final ChecklistVisionPlaygroundService service =
            new ChecklistVisionPlaygroundService(prompts, client, new ObjectMapper());
    private final UUID version = UUID.randomUUID();
    private final byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
    private final MockMultipartFile image = new MockMultipartFile("image", "sample.jpg", "image/jpeg", jpeg);

    @Test
    void submitsSelectedVersionAndReturnsFindingsInCriterionOrder() throws Exception {
        when(prompts.previewVersion(ChecklistVisionPlaygroundService.PROMPT_KEY, version, Map.of()))
                .thenReturn(new PromptDtos.PreviewResult("draft prompt", null));
        var inputs = List.of(new CriterionInput("PHOTO_1", "roof", "roof"),
                new CriterionInput("PHOTO_2", "door", "door"));
        when(client.assessWithPrompt(jpeg, "vi", inputs, "draft prompt")).thenReturn(Map.of(
                "PHOTO_2", new Finding(Verdict.UNCERTAIN, "obscured"),
                "PHOTO_1", new Finding(Verdict.MATCH, "visible")));
        MockMvcBuilders.standaloneSetup(new ChecklistVisionPlaygroundController(service)).build()
                .perform(multipart("/api/admin/ai-ops/checklist-vision/playground").file(image)
                        .param("promptVersionId", version.toString()).param("language", "vi")
                        .param("criteria", "[\"roof\",\"door\"]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.findings[0].code").value("PHOTO_1"))
                .andExpect(jsonPath("$.data.findings[0].verdict").value("MATCH"))
                .andExpect(jsonPath("$.data.findings[1].observation").value("obscured"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "{}", "[1]", "[null]", "[\" \"]", "[\"ok\"] true", "["})
    void rejectsInvalidCriteria(String criteria) {
        var error = assertThrows(ResponseStatusException.class, () -> service.assess(image, version, criteria, "en"));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verifyNoInteractions(prompts, client);
    }

    @Test
    void textPlaygroundRejectsVisionBeforeScoring() {
        var management = new PromptManagementService(null, null, null, null, null);
        var error = assertThrows(ResponseStatusException.class, () ->
                management.runPlaygroundTest(ChecklistVisionPlaygroundService.PROMPT_KEY, version, null));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }
}
