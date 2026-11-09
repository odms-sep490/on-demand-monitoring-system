package com.ondemandmonitoring.order.dto.request;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderCreateRequestValidationTest {

    @Test
    void requiresAnAllowedResultRetentionPeriod() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();

            assertTrue(validator.validate(OrderCreateRequest.builder().build()).stream()
                    .anyMatch(v -> v.getPropertyPath().toString().equals("dataRetentionDays")));

            for (int allowed : new int[] {30, 90, 180, 365}) {
                assertTrue(validator.validate(OrderCreateRequest.builder().dataRetentionDays(allowed).build()).stream()
                        .noneMatch(v -> v.getPropertyPath().toString().equals("dataRetentionDays")),
                        "retention " + allowed);
            }

            assertTrue(validator.validate(OrderCreateRequest.builder().dataRetentionDays(60).build()).stream()
                    .anyMatch(v -> v.getPropertyPath().toString().equals("dataRetentionDays")));
        }
    }

    @Test
    void validatesNestedDeliverableFields() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = OrderCreateRequest.builder()
                    .deliverables(List.of(OrderDeliverableRequest.builder().build()))
                    .build();
            var violations = factory.getValidator().validate(request);
            var paths = violations.stream().map(v -> v.getPropertyPath().toString()).toList();
            assertTrue(paths.contains("deliverables[0].deliverableTypeId"));
            assertTrue(paths.contains("deliverables[0].requirement"));
        }
    }

    @Test
    void acceptsValidNestedDeliverable() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = OrderCreateRequest.builder()
                    .deliverables(List.of(OrderDeliverableRequest.builder()
                            .deliverableTypeId("type-id").requirement(Map.of()).build()))
                    .build();
            var violations = factory.getValidator().validate(request);
            assertTrue(violations.stream().noneMatch(v ->
                    v.getPropertyPath().toString().startsWith("deliverables")));
        }
    }

    @Test
    void validatesChecklistEvidenceMinimumWithinOneToFifty() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var missingDefaults = new OrderChecklistItemRequest();
            var request = OrderCreateRequest.builder().checklistItems(List.of(missingDefaults)).build();
            assertTrue(factory.getValidator().validate(request).stream()
                    .noneMatch(v -> v.getPropertyPath().toString().startsWith("checklistItems")));

            for (int invalid : new int[] {0, 51}) {
                var item = new OrderChecklistItemRequest(); item.setMinimumEvidenceCount(invalid);
                var invalidRequest = OrderCreateRequest.builder().checklistItems(List.of(item)).build();
                assertTrue(factory.getValidator().validate(invalidRequest).stream()
                        .anyMatch(v -> v.getPropertyPath().toString().equals("checklistItems[0].minimumEvidenceCount")));
            }
            var valid = new OrderChecklistItemRequest(); valid.setMinimumEvidenceCount(3);
            var validRequest = OrderCreateRequest.builder().checklistItems(List.of(valid)).build();
            assertTrue(factory.getValidator().validate(validRequest).stream()
                    .noneMatch(v -> v.getPropertyPath().toString().startsWith("checklistItems")));
        }
    }

    @Test
    void rejectsRecurrenceOutsideTwoToFiftyTwoFlights() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            for (int bad : new int[] {1, 53}) {
                var request = OrderCreateRequest.builder().recurrenceOccurrences(bad).build();
                var paths = factory.getValidator().validate(request).stream()
                        .map(v -> v.getPropertyPath().toString()).toList();
                assertTrue(paths.contains("recurrenceOccurrences"), "occurrences " + bad);
            }
            var ok = OrderCreateRequest.builder().recurrenceOccurrences(4).build();
            assertTrue(factory.getValidator().validate(ok).stream()
                    .noneMatch(v -> v.getPropertyPath().toString().equals("recurrenceOccurrences")));
        }
    }
}
