package com.ondemandmonitoring.ai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.ai.config.ChecklistVisionProperties;
import com.ondemandmonitoring.ai.config.OpenRouterProperties;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Explicitly opt in to real, sequential CPU inference; no Spring, database, or object storage. */
@EnabledIfEnvironmentVariable(named = "ODMS_TEST_LOCAL_VISION", matches = "true")
class ChecklistVisionLocalizationLiveTest {
    private static final String MODEL = "qwen3-vl:2b-instruct";
    private static final Pattern VIETNAMESE = Pattern.compile(
            "[ăâđêôơưàáảãạằắẳẵặầấẩẫậèéẻẽẹềếểễệìíỉĩịòóỏõọồốổỗộờớởỡợùúủũụừứửữựỳýỷỹỵ]",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    @ParameterizedTest(name = "real CPU vision response language={0}")
    @ValueSource(strings = {"vi", "en"})
    void localCpuVisionLocalizesLabelAndObservationInBothDirections(String language) throws Exception {
        String base = System.getenv().getOrDefault("OLLAMA_URL", "http://127.0.0.1:11434");
        URI endpoint = URI.create(base);
        assertTrue("http".equals(endpoint.getScheme())
                && ("127.0.0.1".equals(endpoint.getHost()) || "localhost".equals(endpoint.getHost()))
                && endpoint.getRawUserInfo() == null && endpoint.getRawQuery() == null
                && endpoint.getRawFragment() == null
                && (endpoint.getPath().isEmpty() || "/".equals(endpoint.getPath())),
                "OLLAMA_URL must be a plain loopback HTTP origin");

        var properties = new ChecklistVisionProperties();
        properties.setEnabled(true);
        properties.setProvider(ChecklistVisionProperties.Provider.OLLAMA);
        properties.setOllamaBaseUrl(base);
        properties.setOllamaCpuOnly(true);
        properties.setModel(MODEL);
        var mapper = new ObjectMapper();
        var client = new ChecklistImageVisionClient(properties, new OpenRouterProperties(), mapper);
        var original = new ChecklistImageVisionClient.CriterionInput("RED_CIRCLE", "Red circle",
                "A large red circle is visible on a white background.");
        var criteria = List.of(original);
        byte[] jpeg = jpeg();
        try {
            assertTrue(client.available());
            assertEquals(endpoint.resolve("/api/chat").toString(), client.deployment().endpoint());
            var findings = client.assess(jpeg, language, criteria);
            assertEquals(java.util.Set.of("RED_CIRCLE"), findings.keySet());
            var finding = findings.get("RED_CIRCLE");
            // Print actual parsed model output before assertions so a failed run retains evidence.
            System.out.printf("LOCAL_VISION_RESULT language=%s model=%s finding=%s%n",
                    language, MODEL, ascii(mapper.writeValueAsString(finding)));
            assertCpuLoaded(endpoint, mapper);
            assertAll(
                    () -> assertTrue(java.util.EnumSet.allOf(ChecklistImageVisionClient.Verdict.class)
                            .contains(finding.verdict()), "Verdict must be a supported enum value"),
                    () -> assertLocalized(finding.label(), language, 300),
                    () -> assertLocalized(finding.observation(), language, 400),
                    () -> assertTrue(fold(finding.label()).contains("vi".equals(language) ? "do" : "red"),
                            "Label must describe the visible red shape"),
                    () -> assertTrue(fold(finding.observation()).contains("vi".equals(language) ? "do" : "red"),
                            "Observation must describe the image, not merely acknowledge the task"));
            assertEquals("Red circle", original.label());
            assertEquals("A large red circle is visible on a white background.", original.instruction());
        } finally {
            client.close();
        }
    }

    private static String ascii(String text) {
        return text.chars().mapToObj(c -> c > 127 ? String.format("\\u%04x", c) : Character.toString(c))
                .collect(java.util.stream.Collectors.joining());
    }

    private static void assertLocalized(String text, String language, int maxLength) {
        assertNotNull(text);
        assertFalse(text.isBlank());
        assertTrue(text.length() <= maxLength);
        assertFalse(text.chars().anyMatch(Character::isISOControl));
        assertFalse(text.codePoints().filter(Character::isLetter)
                        .anyMatch(c -> Character.UnicodeScript.of(c) != Character.UnicodeScript.LATIN),
                "Unexpected non-Latin script in " + language + " text: " + ascii(text));
        assertEquals("vi".equals(language), VIETNAMESE.matcher(text).find(),
                "Expected " + language + " text, received: " + text);
    }

    private static String fold(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT);
    }

    private static void assertCpuLoaded(URI endpoint, ObjectMapper mapper) throws Exception {
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            var response = http.send(HttpRequest.newBuilder(endpoint.resolve("/api/ps"))
                            .timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, response.statusCode());
            for (var model : mapper.readTree(response.body()).path("models")) {
                if (MODEL.equals(model.path("name").asText())) {
                    assertTrue(model.has("size_vram"), "Ollama must report GPU allocation");
                    assertEquals(0L, model.path("size_vram").asLong(-1));
                    System.out.println("LOCAL_VISION_CPU model=" + MODEL + " size_vram=0");
                    return;
                }
            }
            fail("Expected vision model to remain loaded after inference");
        }
    }

    private static byte[] jpeg() throws Exception {
        var image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 256, 256);
            graphics.setColor(Color.RED);
            graphics.fillOval(48, 48, 160, 160);
        } finally {
            graphics.dispose();
        }
        var output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "jpeg", output));
        return output.toByteArray();
    }
}
