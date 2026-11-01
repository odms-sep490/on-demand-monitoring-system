package com.ondemandmonitoring.mission.mapper;

import static org.junit.jupiter.api.Assertions.*;

import com.ondemandmonitoring.checklist.domain.ChecklistDefinition;
import com.ondemandmonitoring.mission.domain.MissionChecklistAssignment;
import com.ondemandmonitoring.mission.domain.MissionChecklistExecution;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.domain.ChecklistGuidanceContent;
import com.ondemandmonitoring.order.enums.OrderChecklistSourceType;
import com.ondemandmonitoring.finance.dto.ChecklistGuidanceSnapshot;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class MissionChecklistExecutionMapperGuidanceTest {
    private final MissionChecklistExecutionMapper mapper = Mappers.getMapper(MissionChecklistExecutionMapper.class);

    @Test void customUsesAcceptedContentEvenWhenManagerDraftAndVersionHaveChanged() {
        var execution = customExecution();
        var item = execution.getOrderChecklistItem();
        item.setCustomGuidance(new ChecklistGuidanceContent("Later draft", List.of("Draft step"),
                List.of("Draft input"), List.of("Draft output")));
        item.setGuidanceVersion(99);
        item.setMinimumImageCount(2); item.setMinimumVideoCount(1); item.setMinimumEvidenceCount(3);
        var accepted = acceptedItem("custom", 1);

        var response = mapper.toResponse(execution, new MissionChecklistGuidanceContext(
                new ChecklistGuidanceSnapshot(1, List.of(accepted))));

        assertEquals("Accepted description", response.getDescription());
        assertEquals(accepted.executionSteps(), response.getExecutionSteps());
        assertEquals(accepted.inputRequirements(), response.getInputRequirements());
        assertEquals(accepted.expectedOutputs(), response.getExpectedOutputs());
        assertEquals(2, response.getMinimumImageCount()); assertEquals(1, response.getMinimumVideoCount());
        assertEquals(3, response.getMinimumEvidenceCount());
        assertEquals(execution.getExecutionStatus(), response.getExecutionStatus());
        assertEquals(execution.getAssessmentStatus(), response.getAssessmentStatus());
    }

    @Test void legacyCustomSnapshotNeverFallsBackToOrderFieldsOrManagerDraft() {
        var execution = customExecution();
        execution.getOrderChecklistItem().setCustomGuidance(new ChecklistGuidanceContent("Draft", List.of("Draft"), List.of(), List.of()));
        var response = mapper.toResponse(execution, new MissionChecklistGuidanceContext(null));
        assertNull(response.getDescription()); assertNull(response.getExecutionSteps());
        assertNull(response.getInputRequirements()); assertNull(response.getExpectedOutputs());
        assertNull(mapper.toResponse(execution).getDescription());
    }

    @Test void newSnapshotRequiresCustomItemAndMatchingChecklistRevision() {
        for (var entries : List.of(List.<ChecklistGuidanceSnapshot.Item>of(),
                List.of(acceptedItem("other", 1)), List.of(acceptedItem("custom", 2)))) {
            var context = new MissionChecklistGuidanceContext(new ChecklistGuidanceSnapshot(1, entries));
            var error = assertThrows(ApiException.class, () -> mapper.toResponse(customExecution(), context));
            assertEquals(ErrorCode.CHECKLIST_EXECUTION_INTEGRITY_INVALID, error.getErrorCode());
            assertTrue(error.getMessage().contains("custom"));
        }
    }

    @Test void catalogSourcesRetainOrderSnapshotRegardlessOfAcceptedQuoteEntries() {
        for (var source : List.of(OrderChecklistSourceType.SERVICE_TEMPLATE, OrderChecklistSourceType.CUSTOMER_SELECTED)) {
            var execution = customExecution(); execution.getOrderChecklistItem().setSourceType(source);
            var context = new MissionChecklistGuidanceContext(new ChecklistGuidanceSnapshot(1, List.of(acceptedItem("custom", 1))));
            var response = mapper.toResponse(execution, context);
            assertEquals("Order snapshot", response.getDescription());
            assertEquals(List.of("Order step"), response.getExecutionSteps());
            assertEquals(List.of("Order input"), response.getInputRequirements());
            assertEquals(List.of("Order output"), response.getExpectedOutputs());
        }
    }

    private MissionChecklistExecution customExecution() {
        var item = new OrderChecklistItem(); item.setId("custom"); item.setSourceType(OrderChecklistSourceType.CUSTOMER_CUSTOM);
        item.setDescriptionSnapshot("Order snapshot"); item.setExecutionStepsSnapshot(List.of("Order step"));
        item.setInputRequirementsSnapshot(List.of("Order input")); item.setExpectedOutputsSnapshot(List.of("Order output"));
        var execution = new MissionChecklistExecution(); execution.setOrderChecklistItem(item);
        execution.setMissionChecklistAssignment(new MissionChecklistAssignment()); return execution;
    }

    private ChecklistGuidanceSnapshot.Item acceptedItem(String id, long revision) {
        return new ChecklistGuidanceSnapshot.Item(id, revision, 3, "Accepted description", List.of("Accepted step"),
                List.of("Accepted input"), List.of("Accepted output"));
    }

    @Test void crewSeesTheGuidanceSnapshottedOnTheOrderNotTheCatalog() {
        var catalog = new ChecklistDefinition();
        catalog.setDescription("Catalog text edited later");
        catalog.setExecutionSteps(List.of("Catalog step"));
        var item = new OrderChecklistItem();
        item.setContent("Kiểm tra mái");
        item.setSourceChecklist(catalog);
        item.setDescriptionSnapshot("Snapshot text");
        item.setExecutionStepsSnapshot(List.of("Bay lưới song song", "Chụp cận"));
        item.setInputRequirementsSnapshot(List.of("Trời quang"));
        item.setExpectedOutputsSnapshot(List.of("Ảnh toàn mái"));
        var assignment = new MissionChecklistAssignment();
        var execution = new MissionChecklistExecution();
        execution.setOrderChecklistItem(item);
        execution.setMissionChecklistAssignment(assignment);

        var response = mapper.toResponse(execution);

        assertEquals("Snapshot text", response.getDescription());
        assertEquals(List.of("Bay lưới song song", "Chụp cận"), response.getExecutionSteps());
        assertEquals(List.of("Trời quang"), response.getInputRequirements());
        assertEquals(List.of("Ảnh toàn mái"), response.getExpectedOutputs());
    }

    @Test void itemsWithoutGuidanceMapToNull() {
        var item = new OrderChecklistItem();
        item.setContent("Mục tùy chỉnh");
        var execution = new MissionChecklistExecution();
        execution.setOrderChecklistItem(item);
        execution.setMissionChecklistAssignment(new MissionChecklistAssignment());

        var response = mapper.toResponse(execution);

        assertNull(response.getDescription());
        assertNull(response.getExecutionSteps());
    }
}
