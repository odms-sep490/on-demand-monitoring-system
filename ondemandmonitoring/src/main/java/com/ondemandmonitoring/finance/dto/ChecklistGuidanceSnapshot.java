package com.ondemandmonitoring.finance.dto;

import java.util.List;

/** Immutable scope shown to the customer for this particular quote version. */
public record ChecklistGuidanceSnapshot(int schemaVersion, List<Item> items) {
    public ChecklistGuidanceSnapshot {
        items = List.copyOf(items);
    }

    public record Item(String itemId, long checklistRevision, long guidanceVersion,
            String description, List<String> executionSteps, List<String> inputRequirements,
            List<String> expectedOutputs) {
        public Item {
            executionSteps = List.copyOf(executionSteps);
            inputRequirements = List.copyOf(inputRequirements);
            expectedOutputs = List.copyOf(expectedOutputs);
        }
    }
}
