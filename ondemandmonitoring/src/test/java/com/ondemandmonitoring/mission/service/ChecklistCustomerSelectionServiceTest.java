package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.checklist.config.ChecklistDefaults;
import com.ondemandmonitoring.checklist.domain.ChecklistDefinition;
import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.imagequality.DeliveryImageQualityReader;
import com.ondemandmonitoring.imagequality.DeliveryImageQualityReader.Signal;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.*;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.dto.request.*;
import com.ondemandmonitoring.mission.dto.response.ChecklistExecutionResponse;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.inspection.service.impl.InspectionCorrectionMutationGuard;
import com.ondemandmonitoring.mission.inspection.service.impl.InspectionCorrectionPolicy;
import com.ondemandmonitoring.mission.mapper.ChecklistEvidenceMapper;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.impl.*;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.enums.*;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChecklistCustomerSelectionServiceTest {
    final com.ondemandmonitoring.order.repository.OrderRepository orders = mock(com.ondemandmonitoring.order.repository.OrderRepository.class);
    final com.ondemandmonitoring.delivery.repository.ResultReviewRoundEvidenceRepository publishedEvidence = mock(com.ondemandmonitoring.delivery.repository.ResultReviewRoundEvidenceRepository.class);
    final MissionRepository missions = mock(MissionRepository.class);
    final MissionChecklistExecutionRepository executions = mock(MissionChecklistExecutionRepository.class);
    final MissionChecklistEvidenceRepository evidence = mock(MissionChecklistEvidenceRepository.class);
    final MediaAssetRepository media = mock(MediaAssetRepository.class);
    final MissionResultRepository results = mock(MissionResultRepository.class);
    final IMissionAuthorizationService auth = mock(IMissionAuthorizationService.class);
    final DeliveryImageQualityReader quality = mock(DeliveryImageQualityReader.class);
    final AuthenticatedUserResolver actor = mock(AuthenticatedUserResolver.class);
    final ChecklistEvidenceServiceImpl service = new ChecklistEvidenceServiceImpl(missions,
                executions,
                evidence,
                media,
                results,
                auth,
                actor,
                new ChecklistEvidencePolicy(),
                org.mapstruct.factory.Mappers.getMapper(ChecklistEvidenceMapper.class),
                mock(IMediaObjectStorage.class),
                org.mockito.Mockito.mock(com.ondemandmonitoring.media.service.impl.MediaDerivativeService.class),
                orders,
                publishedEvidence,
                new ChecklistDeliverySuggestionPolicy(quality));
    final List<MissionChecklistEvidence> links = new ArrayList<>();
    Mission mission;
    MissionChecklistExecution execution;
    OrderChecklistItem item;

    @BeforeEach void setup() {
        var order = new Order(); order.setId("o"); order.getExecutionPlan().setStatus(ExecutionPlanStatus.FINALIZED);
        order.getExecutionPlan().setRevision(1);
        mission = new Mission(); mission.setId("m"); mission.setOrder(order); mission.setStatus(MissionStatus.IN_PROGRESS);
        mission.setActualEndAt(Instant.now());
        item = new OrderChecklistItem(); item.setId("i"); item.setOrder(order); item.setEvidencePolicyVersion(2);
        item.setMinimumEvidenceCount(2); item.setRequiredEvidenceType(RequiredEvidenceType.IMAGE);
        execution = new MissionChecklistExecution(); execution.setId("e"); execution.setMissionId("m");
        execution.setOrderId("o"); execution.setOrderChecklistItemId("i"); execution.setOrderChecklistItem(item);
        execution.setVersion(0L); execution.setExecutionStatus(ChecklistExecutionStatus.COMPLETED);
        execution.setAssessmentStatus(ChecklistAssessmentStatus.COMPLIANT); execution.setAssessmentNote("Reviewed");
        execution.setAssessedBy("inspector"); execution.setAssessedAt(Instant.now());
        ScopeTestFixture.link(execution, mission);
        ReflectionTestUtils.setField(service, "inspectionMutation", mock(InspectionCorrectionMutationGuard.class));
        when(missions.findOrderIdByMissionId("m")).thenReturn(Optional.of("o"));
        when(missions.findById("m")).thenReturn(Optional.of(mission));
        when(orders.findByIdForUpdate("o")).thenReturn(Optional.of(order));
        when(missions.findByIdForUpdate("m")).thenReturn(Optional.of(mission));
        when(executions.findByIdForUpdate("e")).thenReturn(Optional.of(execution));
        when(executions.findById("e")).thenReturn(Optional.of(execution));
        when(evidence.findActiveByMissionId("m")).thenAnswer(call -> links.stream().filter(e -> e.getDetachedAt() == null).toList());
        when(auth.canSelectCustomerEvidence("m")).thenReturn(true);
        when(auth.canAttachChecklistEvidence("m")).thenReturn(true);
        when(auth.canDetachChecklistEvidence("m")).thenReturn(true);
        when(auth.canViewMission("m")).thenReturn(true);
        when(actor.getCurrentUserId()).thenReturn("inspector");
        when(executions.saveAndFlush(execution)).thenAnswer(call -> { execution.setVersion(execution.getVersion() + 1); return execution; });
    }

    MissionChecklistEvidence link(String id, String type) {
        var asset = new MediaAsset(); asset.setId("media-" + id); asset.setMissionId("m"); asset.setType(type);
        asset.setSourceType("MANUAL_UPLOAD"); asset.setMediaStatus(MediaStatus.AVAILABLE); asset.setValidatedAt(Instant.now());
        asset.setChecksumSha256(id);
        var link = new MissionChecklistEvidence(); link.setId(id); link.setMissionId("m");
        link.setMissionChecklistExecution(execution); link.setMediaAsset(asset); link.setAttachedAt(Instant.now());
        links.add(link);
        when(media.findByIdForUpdate(asset.getId())).thenReturn(Optional.of(asset));
        when(evidence.findById(id)).thenReturn(Optional.of(link));
        return link;
    }
    List<com.ondemandmonitoring.mission.dto.response.ChecklistEvidenceResponse> select(boolean surplus, String... ids) {
        return service.selectForCustomer("m", "e", new ChecklistCustomerSelectionRequest(execution.getVersion(), List.of(ids), surplus));
    }
    void error(ErrorCode code, Runnable action) { assertEquals(code, assertThrows(ApiException.class, action::run).getErrorCode()); }

    @Test void resolvesOnlyCurrentApprovedLinkedImagePreviewForSupportedSnapshot() {
        var definition = new ChecklistDefinition();
        definition.setSeedCode(ChecklistDefaults.code(8));
        item.setSourceChecklist(definition);
        item.setContent(ChecklistDefaults.CONTENTS.get(7));
        var image = link("roof", "IMAGE").getMediaAsset();
        image.setS3Bucket("private-bucket");
        image.setS3Key("original.jpg");
        image.setPreviewKey("preview.jpg");
        image.setDerivativeStatus(MediaDerivativeStatus.READY);
        var request = new ChecklistImageContentAssessmentRequest(0L, "en");

        var target = service.resolveContentAssessment("m", "e", "roof", request);
        assertEquals("preview.jpg", target.previewKey());
        assertEquals(List.of("ROOF_VISIBLE", "ROOF_INSPECTABLE"),
                target.criteria().stream().map(criterion -> criterion.code()).toList());
        assertTrue(service.getDeliverySuggestion("m", "e").contentAssessmentAvailable());
        image.setPreviewKey(image.getS3Key());
        assertFalse(service.getDeliverySuggestion("m", "e").contentAssessmentAvailable());
        error(ErrorCode.MEDIA_DERIVATIVE_UNAVAILABLE,
                () -> service.resolveContentAssessment("m", "e", "roof", request));
        image.setPreviewKey("preview.jpg");
        item.setContent("Different accepted checklist");
        error(ErrorCode.INVALID_REQUEST, () -> service.resolveContentAssessment("m", "e", "roof", request));
        error(ErrorCode.CONCURRENT_UPDATE, () -> service.resolveContentAssessment("m", "e", "roof",
                new ChecklistImageContentAssessmentRequest(1L, "en")));
        when(auth.canSelectCustomerEvidence("m")).thenReturn(false);
        error(ErrorCode.ACCESS_DENIED, () -> service.resolveContentAssessment("m", "e", "roof", request));
        verify(evidence, never()).saveAllAndFlush(any());
    }

    @Test void suggestionReadsOnlyEligibleImagesFromCurrentExecutionAndDoesNotChangeSelection() {
        var image = link("image", "IMAGE");
        var selected = link("video", "VIDEO"); selected.setSelectedForCustomer(true);
        link("rejected", "IMAGE").getMediaAsset().setMediaStatus(MediaStatus.REJECTED);
        link("detached", "IMAGE").setDetachedAt(Instant.now());
        var sibling = new MissionChecklistExecution(); sibling.setId("sibling"); sibling.setMissionId("m");
        link("sibling", "IMAGE").setMissionChecklistExecution(sibling);
        item.setEvidencePolicyVersion(3); item.setRequiredEvidenceType(RequiredEvidenceType.IMAGE_AND_VIDEO);
        item.setMinimumImageCount(2); item.setMinimumVideoCount(1); item.setMinimumEvidenceCount(3);
        execution.setVersion(7L);
        when(quality.read("m", List.of(image.getMediaAsset())))
                .thenReturn(Map.of("media-image", new Signal("check", null, "PASS", 0L)));

        var suggestion = service.getDeliverySuggestion("m", "e");

        assertEquals(7L, suggestion.executionVersion());
        assertEquals(2, suggestion.requiredImageCount());
        assertEquals(1, suggestion.suggestedImageCount());
        assertEquals(List.of("image"), suggestion.suggestedImageEvidenceIds());
        assertEquals(List.of("image"), suggestion.candidates().stream().map(candidate -> candidate.evidenceId()).toList());
        assertEquals(List.of("INSUFFICIENT_RECOMMENDABLE_IMAGES"), suggestion.warnings());
        assertFalse(image.isSelectedForCustomer());
        assertTrue(selected.isSelectedForCustomer());
        assertEquals(7L, execution.getVersion());
        verify(executions, never()).findByIdForUpdate(anyString());
        verify(executions, never()).saveAndFlush(any());
        verify(evidence, never()).saveAllAndFlush(any());
        verifyNoInteractions(media, orders);
    }

    @Test void suggestionUsesDistinctEligibleSelectedVideosForLegacyMixedQuotas() {
        item.setRequiredEvidenceType(RequiredEvidenceType.IMAGE_OR_VIDEO);
        var first = link("first", "VIDEO"); first.setSelectedForCustomer(true);
        var copy = link("copy", "VIDEO"); copy.setSelectedForCustomer(true);
        copy.getMediaAsset().setChecksumSha256("FIRST");
        var rejected = link("rejected", "VIDEO"); rejected.setSelectedForCustomer(true);
        rejected.getMediaAsset().setMediaStatus(MediaStatus.REJECTED);
        link("unselected", "VIDEO");
        assertEquals(1, service.getDeliverySuggestion("m", "e").requiredImageCount());

        item.setEvidencePolicyVersion(1); item.setMinimumEvidenceCount(1);
        assertEquals(0, service.getDeliverySuggestion("m", "e").requiredImageCount());

        item.setEvidencePolicyVersion(2); item.setRequiredEvidenceType(RequiredEvidenceType.IMAGE_AND_VIDEO);
        assertEquals(1, service.getDeliverySuggestion("m", "e").requiredImageCount());

        item.setRequiredEvidenceType(RequiredEvidenceType.VIDEO);
        link("image", "IMAGE");
        var videoOnly = service.getDeliverySuggestion("m", "e");
        assertEquals(0, videoOnly.requiredImageCount());
        assertTrue(videoOnly.candidates().isEmpty());
        assertTrue(videoOnly.warnings().isEmpty());
    }

    @Test void suggestionSharesSelectionPermissionPublicationAndExecutionGuards() {
        when(auth.canSelectCustomerEvidence("m")).thenReturn(false);
        error(ErrorCode.ACCESS_DENIED, () -> service.getDeliverySuggestion("m", "e"));
        when(auth.canSelectCustomerEvidence("m")).thenReturn(true);
        when(publishedEvidence.existsByMediaAsset_Mission_Id("m")).thenReturn(true);
        error(ErrorCode.CHECKLIST_EXECUTION_LOCKED, () -> service.getDeliverySuggestion("m", "e"));
        when(publishedEvidence.existsByMediaAsset_Mission_Id("m")).thenReturn(false);
        execution.setAssessmentStatus(ChecklistAssessmentStatus.NON_COMPLIANT);
        error(ErrorCode.CHECKLIST_NOT_READY, () -> service.getDeliverySuggestion("m", "e"));
        execution.setMissionId("other");
        error(ErrorCode.CHECKLIST_EXECUTION_PARENT_MISMATCH, () -> service.getDeliverySuggestion("m", "e"));
        execution.setMissionId("m");
        execution.getMissionChecklistAssignment().setRemovedAt(Instant.now());
        error(ErrorCode.CHECKLIST_EXECUTION_INTEGRITY_INVALID, () -> service.getDeliverySuggestion("m", "e"));
        verifyNoInteractions(quality);
    }

    @Test void draftBelowMinimumIsSavedAndSelectionDoesNotChangeAssessment() {
        var one = link("one", "IMAGE"); var reserve = link("reserve", "IMAGE");
        var assessedAt = execution.getAssessedAt();
        var response = select(false, "one");
        assertTrue(response.getFirst().isSelectedForCustomer()); assertEquals("inspector", response.getFirst().getSelectedBy());
        assertNotNull(response.getFirst().getSelectedAt()); assertFalse(reserve.isSelectedForCustomer());
        assertEquals(1L, execution.getVersion()); assertEquals(assessedAt, execution.getAssessedAt());
        assertEquals(ChecklistAssessmentStatus.COMPLIANT, execution.getAssessmentStatus());
        assertFalse(service.readyForInspectorApproval(execution, links));
        assertFalse(service.enrich(new ChecklistExecutionResponse(), execution, links).isReadyForFinalApproval());
        var order = inOrder(orders, missions, executions, media);
        order.verify(missions).findOrderIdByMissionId("m");
        order.verify(orders).findByIdForUpdate("o");
        order.verify(missions).findByIdForUpdate("m"); order.verify(executions).findByIdForUpdate("e");
        order.verify(media).findByIdForUpdate(one.getMediaAsset().getId());
    }

    @Test void identicalSelectionPreservesAuditAndVersionButStillChecksExpectedVersion() {
        var one = link("one", "IMAGE"); select(false, "one");
        var selectedAt = one.getSelectedAt(); select(false, "one");
        assertEquals(1L, execution.getVersion()); assertEquals(selectedAt, one.getSelectedAt());
        verify(executions, times(1)).saveAndFlush(execution);
        error(ErrorCode.CONCURRENT_UPDATE, () -> service.selectForCustomer("m", "e",
                new ChecklistCustomerSelectionRequest(0L, List.of("one"), false)));
        select(false); assertFalse(one.isSelectedForCustomer()); assertNull(one.getSelectedBy()); assertNull(one.getSelectedAt());
        assertEquals(2L, execution.getVersion()); select(false); assertEquals(2L, execution.getVersion());
    }

    @Test void onlyInspectorSelectionPermissionIsAccepted() {
        link("one", "IMAGE"); when(auth.canSelectCustomerEvidence("m")).thenReturn(false);
        error(ErrorCode.ACCESS_DENIED, () -> select(false, "one"));
        verify(executions, never()).findByIdForUpdate(anyString()); verify(evidence, never()).saveAllAndFlush(any());
    }

    @Test void duplicateUnknownAndCrossExecutionIdsAreRejectedWithoutChanges() {
        var one = link("one", "IMAGE");
        error(ErrorCode.INVALID_REQUEST, () -> select(false, "one", "one"));
        error(ErrorCode.INVALID_REQUEST, () -> select(false, "unknown"));
        var foreign = link("foreign", "IMAGE");
        var otherExecution = new MissionChecklistExecution(); otherExecution.setId("other"); otherExecution.setMissionId("m");
        foreign.setMissionChecklistExecution(otherExecution);
        error(ErrorCode.INVALID_REQUEST, () -> select(false, "foreign"));
        assertFalse(one.isSelectedForCustomer()); verify(executions, never()).saveAndFlush(any());
    }

    @Test void wrongMissionExecutionAndDetachedEvidenceCannotBeSelected() {
        var one = link("one", "IMAGE"); one.setDetachedAt(Instant.now());
        error(ErrorCode.INVALID_REQUEST, () -> select(false, "one"));
        execution.setMissionId("another");
        error(ErrorCode.CHECKLIST_EXECUTION_PARENT_MISMATCH, () -> select(false));
    }

    @ParameterizedTest @EnumSource(value = MediaStatus.class, names = {"PENDING_MANAGER_APPROVAL", "REJECTED", "UPLOAD_PENDING"})
    void selectionRequiresCurrentApprovedMedia(MediaStatus status) {
        var one = link("one", "IMAGE"); one.getMediaAsset().setMediaStatus(status);
        error(ErrorCode.EVIDENCE_NOT_ELIGIBLE, () -> select(false, "one")); assertFalse(one.isSelectedForCustomer());
    }

    @Test void typeStorageValidationAndCrossMissionMediaAreChecked() {
        var one = link("one", "VIDEO");
        error(ErrorCode.EVIDENCE_NOT_ELIGIBLE, () -> select(false, "one"));
        one.getMediaAsset().setType("IMAGE"); one.getMediaAsset().setValidatedAt(null);
        error(ErrorCode.EVIDENCE_NOT_ELIGIBLE, () -> select(false, "one"));
        one.getMediaAsset().setValidatedAt(Instant.now()); one.getMediaAsset().setStorageStatus(MediaStorageStatus.DELETE_PENDING);
        error(ErrorCode.EVIDENCE_NOT_ELIGIBLE, () -> select(false, "one"));
        one.getMediaAsset().setStorageStatus(MediaStorageStatus.AVAILABLE); one.getMediaAsset().setMissionId("other");
        error(ErrorCode.CHECKLIST_EXECUTION_INTEGRITY_INVALID, () -> select(false, "one"));
    }

    @Test void reserveEvidenceDoesNotBlockStrictApprovalAndDuplicateChecksumsCannotMeetMinimum() {
        var one = link("one", "IMAGE"); var two = link("two", "IMAGE"); var reserve = link("reserve", "IMAGE");
        reserve.getMediaAsset().setMediaStatus(MediaStatus.REJECTED);
        two.getMediaAsset().setChecksumSha256("ONE"); select(false, "one", "two");
        assertFalse(service.readyForInspectorApproval(execution, links));
        two.getMediaAsset().setChecksumSha256("different"); assertTrue(service.readyForInspectorApproval(execution, links));
        assertFalse(service.ready(execution, links, true), "Legacy approval still considers all attachments");
        assertTrue(service.enrich(new ChecklistExecutionResponse(), execution, links).isReadyForFinalApproval());
        one.getMediaAsset().setMediaStatus(MediaStatus.REJECTED); assertFalse(service.readyForInspectorApproval(execution, links));
    }

    @Test void v3SurplusUsesEachTypeThresholdEvenWhenLegacyCountDiffers() {
        item.setEvidencePolicyVersion(3); item.setMinimumImageCount(3); item.setMinimumVideoCount(1);
        item.setMinimumEvidenceCount(1); item.setRequiredEvidenceType(RequiredEvidenceType.IMAGE_AND_VIDEO);
        link("one", "IMAGE"); link("two", "IMAGE"); link("three", "IMAGE"); link("video", "VIDEO");
        assertEquals(4, select(false, "one", "two", "three", "video").size());
        link("extra", "VIDEO");
        error(ErrorCode.DELIVERY_SURPLUS_CONFIRMATION_REQUIRED, () -> select(false, "one", "two", "extra", "video"));
        select(true, "one", "two", "extra", "video");
        assertFalse(service.readyForInspectorApproval(execution, links), "Two videos cannot replace the third image");
    }

    @Test void legacyZeroThresholdStillRequiresSurplusConfirmation() {
        item.setEvidencePolicyVersion(0); item.setMinimumEvidenceCount(0); link("one", "IMAGE");
        error(ErrorCode.DELIVERY_SURPLUS_CONFIRMATION_REQUIRED, () -> select(false, "one"));
        select(true, "one"); assertEquals(1L, execution.getVersion());
    }

    @Test void attachmentAndDetachInvalidateAcceptanceButDuplicateAttachDoesNot() {
        var one = link("one", "IMAGE"); one.setSelectedForCustomer(true);
        when(evidence.findByMissionChecklistExecution_IdAndMediaAsset_IdAndDetachedAtIsNull("e", "media-one"))
                .thenReturn(Optional.of(one));
        service.attach("m", new BatchChecklistEvidenceRequest("media-one",
                List.of(new BatchChecklistEvidenceRequest.Target("e", 0L)), null));
        assertEquals(ChecklistAssessmentStatus.COMPLIANT, execution.getAssessmentStatus()); assertEquals(0L, execution.getVersion());
        service.detach("m", "e", "one", 0L, "replace");
        assertEquals(ChecklistAssessmentStatus.NOT_ASSESSED, execution.getAssessmentStatus());
        assertNull(execution.getAssessmentNote()); assertNull(execution.getAssessedBy()); assertNull(execution.getAssessedAt());
        assertFalse(service.readyForInspectorApproval(execution, links));
        execution.setAssessmentStatus(ChecklistAssessmentStatus.COMPLIANT); execution.setAssessmentNote("Rechecked");
        var two = link("two", "IMAGE");
        when(evidence.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        service.attach("m", new BatchChecklistEvidenceRequest(two.getMediaAsset().getId(),
                List.of(new BatchChecklistEvidenceRequest.Target("e", execution.getVersion())), null));
        assertEquals(ChecklistAssessmentStatus.NOT_ASSESSED, execution.getAssessmentStatus()); assertNull(execution.getAssessmentNote());
    }

    @Test void pendingOrApprovedResultLocksSelection() {
        var result = new MissionResult(); when(results.findByMissionId("m")).thenReturn(Optional.of(result));
        for (var status : List.of(MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL, MissionResultApprovalStatus.APPROVED)) {
            result.setApprovalStatus(status); error(ErrorCode.CHECKLIST_EXECUTION_LOCKED, () -> select(false));
        }
    }
    @ParameterizedTest
    @EnumSource(value = MediaStatus.class, names = {"PENDING_MANAGER_APPROVAL", "REJECTED"})
    void approvedInspectorPackageRemainsStrictlyReadyWithUnselectedReserve(MediaStatus reserveStatus) {
        item.setMinimumEvidenceCount(1);
        var selected = link("selected", "IMAGE");
        var reserve = link("reserve", "IMAGE"); reserve.getMediaAsset().setMediaStatus(reserveStatus);
        select(false, "selected");
        assertTrue(selected.isSelectedForCustomer()); assertFalse(reserve.isSelectedForCustomer());
        mission.setStatus(MissionStatus.COMPLETED); mission.setCompletedAt(Instant.now());
        var result = new MissionResult(); result.setApprovalStatus(MissionResultApprovalStatus.APPROVED);
        result.setInspectorConfirmed(true); when(results.findByMissionId("m")).thenReturn(Optional.of(result));
        when(missions.findById("m")).thenReturn(Optional.of(mission));
        when(executions.findOrderedByMissionId("m")).thenReturn(List.of(execution));
        var checklist = new MissionChecklistExecutionServiceImpl(missions, ScopeTestFixture.validScope(),
                executions, results, auth, actor,
                org.mapstruct.factory.Mappers.getMapper(com.ondemandmonitoring.mission.mapper.MissionChecklistExecutionMapper.class),
                service, mock(com.ondemandmonitoring.finance.service.IAcceptedChecklistGuidanceService.class));
        ReflectionTestUtils.setField(checklist, "inspectionCorrections", mock(InspectionCorrectionPolicy.class));

        assertTrue(checklist.isReadyForSubmission(mission), "Reserve media cannot block the operational minimum");
        assertTrue(checklist.isReadyForInspectorApproval(mission));
        assertDoesNotThrow(() -> checklist.requireReadyForInspectorApproval(mission));
        var response = checklist.getByMissionId("m");
        assertTrue(response.readyForFinalApproval());
        assertTrue(response.executions().getFirst().isReadyForFinalApproval());
        assertFalse(checklist.isReadyForFinalApproval(mission), "Legacy approval retains all-attachment validation");
        assertEquals(ErrorCode.CHECKLIST_NOT_READY,
                assertThrows(ApiException.class, () -> checklist.requireReadyForFinalApproval(mission)).getErrorCode());
    }

    @Test void approvedUnpublishedInspectorPackageAllowsOnlyCompleteSelectionCorrection() {
        var original = link("original", "IMAGE"); var retained = link("retained", "IMAGE");
        var replacement = link("replacement", "IMAGE");
        original.setSelectedForCustomer(true); retained.setSelectedForCustomer(true);
        var assessedAt = execution.getAssessedAt();
        var result = new MissionResult(); result.setApprovalStatus(MissionResultApprovalStatus.APPROVED);
        result.setInspectorConfirmed(true); when(results.findByMissionId("m")).thenReturn(Optional.of(result));
        error(ErrorCode.CHECKLIST_NOT_READY, () -> select(false, "replacement"));
        assertTrue(original.isSelectedForCustomer()); assertTrue(retained.isSelectedForCustomer());
        assertFalse(replacement.isSelectedForCustomer()); assertEquals(0L, execution.getVersion());
        error(ErrorCode.CHECKLIST_NOT_READY, () -> select(false));
        replacement.getMediaAsset().setChecksumSha256(retained.getMediaAsset().getChecksumSha256());
        error(ErrorCode.CHECKLIST_NOT_READY, () -> select(false, "retained", "replacement"));
        replacement.getMediaAsset().setChecksumSha256("different");
        select(false, "retained", "replacement");
        assertFalse(original.isSelectedForCustomer()); assertTrue(replacement.isSelectedForCustomer());
        assertEquals(1L, execution.getVersion()); assertEquals(assessedAt, execution.getAssessedAt());
        assertEquals(ChecklistAssessmentStatus.COMPLIANT, execution.getAssessmentStatus());
        assertTrue(service.readyForInspectorApproval(execution, links));
        select(false, "retained", "replacement"); assertEquals(1L, execution.getVersion());
        error(ErrorCode.CONCURRENT_UPDATE, () -> service.selectForCustomer("m", "e",
                new ChecklistCustomerSelectionRequest(0L, List.of("original", "retained"), false)));
        error(ErrorCode.CHECKLIST_EXECUTION_LOCKED, () -> service.attach("m", new BatchChecklistEvidenceRequest(
                "media-original", List.of(new BatchChecklistEvidenceRequest.Target("e", 1L)), null)));
    }

    @Test void publicationLocksSelectionEvenIfCapabilityWasPreviouslyGranted() {
        link("one", "IMAGE"); when(publishedEvidence.existsByMediaAsset_Mission_Id("m")).thenReturn(true);
        error(ErrorCode.CHECKLIST_EXECUTION_LOCKED, () -> select(false, "one"));
        verify(executions, never()).findByIdForUpdate(anyString()); verify(executions, never()).saveAndFlush(any());
    }

}
