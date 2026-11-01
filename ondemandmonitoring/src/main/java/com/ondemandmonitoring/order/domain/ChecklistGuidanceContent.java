package com.ondemandmonitoring.order.domain;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Plain-text custom guidance JSON value. Call normalize before comparing or saving
 * manager input; the constructor only makes defensive copies for JSON hydration.
 * Completeness is independent of manager confirmation and quote permissions.
 */
public record ChecklistGuidanceContent(String description, List<String> executionSteps,
        List<String> inputRequirements, List<String> expectedOutputs) {
    public static final int MAX_DESCRIPTION_LENGTH = 4000;
    public static final int MAX_SECTION_ITEMS = 20;
    public static final int MAX_ITEM_LENGTH = 500;

    public ChecklistGuidanceContent {
        executionSteps = immutable(executionSteps);
        inputRequirements = immutable(inputRequirements);
        expectedOutputs = immutable(expectedOutputs);
    }

    public static ChecklistGuidanceContent normalize(ChecklistGuidanceContent content) {
        return content == null ? normalize(null, null, null, null)
                : normalize(content.description(), content.executionSteps(),
                        content.inputRequirements(), content.expectedOutputs());
    }

    /** Null/blank description becomes null; null lists become empty lists. */
    public static ChecklistGuidanceContent normalize(String description, List<String> executionSteps,
            List<String> inputRequirements, List<String> expectedOutputs) {
        String text = description == null ? null : description.strip();
        if (text != null && text.isBlank()) text = null;
        if (text != null && text.length() > MAX_DESCRIPTION_LENGTH) {
            throw invalid("description must contain at most 4000 characters");
        }
        return new ChecklistGuidanceContent(text, normalizeSection("executionSteps", executionSteps),
                normalizeSection("inputRequirements", inputRequirements),
                normalizeSection("expectedOutputs", expectedOutputs));
    }

    public static List<String> missingSections(ChecklistGuidanceContent content) {
        ChecklistGuidanceContent normalized = normalize(content);
        List<String> missing = new ArrayList<>();
        if (normalized.executionSteps().isEmpty()) missing.add("executionSteps");
        if (normalized.inputRequirements().isEmpty()) missing.add("inputRequirements");
        if (normalized.expectedOutputs().isEmpty()) missing.add("expectedOutputs");
        return List.copyOf(missing);
    }

    public static boolean isComplete(ChecklistGuidanceContent content) {
        return missingSections(content).isEmpty();
    }

    private static List<String> normalizeSection(String name, List<String> lines) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (lines != null) {
            for (String line : lines) {
                if (line == null || line.isBlank()) continue;
                String value = line.strip();
                if (value.length() > MAX_ITEM_LENGTH) {
                    throw invalid(name + " items must contain at most 500 characters");
                }
                values.add(value);
            }
        }
        if (values.size() > MAX_SECTION_ITEMS) {
            throw invalid(name + " must contain at most 20 items");
        }
        return List.copyOf(values);
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, message);
    }
}
