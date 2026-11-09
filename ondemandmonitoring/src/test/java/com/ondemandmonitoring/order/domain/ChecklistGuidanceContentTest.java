package com.ondemandmonitoring.order.domain;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChecklistGuidanceContentTest {
    @Test void normalizationStripsDropsBlanksAndDeduplicatesInOrder() {
        var normalized = ChecklistGuidanceContent.normalize("  description  ",
                Arrays.asList("  First ", null, "", "   ", "Second", "First"), null, List.of(" photo "));
        assertEquals("description", normalized.description());
        assertEquals(List.of("First", "Second"), normalized.executionSteps());
        assertEquals(List.of(), normalized.inputRequirements());
        assertEquals(List.of("photo"), normalized.expectedOutputs());
        assertEquals(normalized, ChecklistGuidanceContent.normalize(normalized));
        assertEquals(List.of("inputRequirements"), ChecklistGuidanceContent.missingSections(normalized));
        assertFalse(ChecklistGuidanceContent.isComplete(normalized));
    }

    @Test void emptyDraftAndOptionalDescriptionAreSupported() {
        assertEquals(ChecklistGuidanceContent.normalize(null),
                ChecklistGuidanceContent.normalize("  ", List.of(), null, List.of("  ")));
        assertEquals(List.of("executionSteps", "inputRequirements", "expectedOutputs"),
                ChecklistGuidanceContent.missingSections(null));
        assertTrue(ChecklistGuidanceContent.isComplete(
                ChecklistGuidanceContent.normalize(null, List.of("Fly"), List.of("No special input"), List.of("Photo"))));
    }

    @Test void normalizedLimitsAllowBoundaryAndRejectOverflow() {
        var twenty = IntStream.range(0, 20).mapToObj(i -> "Step " + i).toList();
        assertDoesNotThrow(() -> ChecklistGuidanceContent.normalize("x".repeat(4000), twenty,
                List.of("x".repeat(500)), List.of("Photo")));
        assertInvalid(() -> ChecklistGuidanceContent.normalize("x".repeat(4001), null, null, null));
        assertInvalid(() -> ChecklistGuidanceContent.normalize(null, List.of("x".repeat(501)), null, null));
        assertInvalid(() -> ChecklistGuidanceContent.normalize(null, null, List.of("x".repeat(501)), null));
        assertInvalid(() -> ChecklistGuidanceContent.normalize(null, null, null, List.of("x".repeat(501))));
        var twentyOne = IntStream.range(0, 21).mapToObj(i -> "Step " + i).toList();
        assertInvalid(() -> ChecklistGuidanceContent.normalize(null, twentyOne, null, null));
        assertInvalid(() -> ChecklistGuidanceContent.normalize(null, null, twentyOne, null));
        assertInvalid(() -> ChecklistGuidanceContent.normalize(null, null, null, twentyOne));
    }

    @Test void valueObjectDefensivelyCopiesListsAndKeepsPlainText() {
        var lines = new ArrayList<>(List.of("<b>Photograph roof</b>"));
        var content = ChecklistGuidanceContent.normalize(null, lines, null, null);
        lines.clear();
        assertEquals(List.of("<b>Photograph roof</b>"), content.executionSteps());
        assertThrows(UnsupportedOperationException.class, () -> content.executionSteps().add("Changed"));
    }

    private void assertInvalid(Runnable operation) {
        assertEquals(ErrorCode.VALIDATION_ERROR, assertThrows(ApiException.class, operation::run).getErrorCode());
    }
}
