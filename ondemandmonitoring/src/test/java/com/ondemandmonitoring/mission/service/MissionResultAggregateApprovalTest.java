package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.IMediaAssetService;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.dto.request.MissionResultReviewRequest;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.mapper.MissionResultMapper;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.impl.MissionResultService;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.*;
import com.ondemandmonitoring.order.service.IOrderExecutionCompletionPolicy;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MissionResultAggregateApprovalTest {
    MissionResultRepository results = mock(MissionResultRepository.class);
    MissionRepository missions = mock(MissionRepository.class);
    MediaAssetRepository media = mock(MediaAssetRepository.class);
    MissionResultMapper mapper = mock(MissionResultMapper.class);
    IMissionChecklistExecutionService checklist = mock(IMissionChecklistExecutionService.class);
    IOrderExecutionCompletionPolicy completion = mock(IOrderExecutionCompletionPolicy.class);
    AuthenticatedUserResolver user = mock(AuthenticatedUserResolver.class);
    MissionResultService service = new MissionResultService(results, missions, media, mock(IMediaAssetService.class),
            mapper, completion, checklist, user, mock(IMissionAuthorizationService.class));
    Order order; Mission mission; MissionResult result; IOrderExecutionCompletionPolicy.LockedExecution aggregate;

    @BeforeEach void setup() {
        order = new Order(); order.setId("o"); order.setOrderStatus(OrderStatus.APPROVED);
        order.getExecutionPlan().setStatus(ExecutionPlanStatus.FINALIZED); order.getExecutionPlan().setRevision(4);
        mission = new Mission(); mission.setId("m"); mission.setOrder(order); mission.setStatus(MissionStatus.COMPLETED);
        result = new MissionResult(); result.setId("r"); result.setMission(mission); result.setStatus(MissionResultStatus.COMPLETED);
        result.setApprovalStatus(MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL);
        aggregate = new IOrderExecutionCompletionPolicy.LockedExecution(order, List.of(mission));
        when(results.findOrderIdByResultId("r")).thenReturn(Optional.of("o"));
        when(completion.lockAggregate("o")).thenReturn(aggregate);
        when(results.findById("r")).thenReturn(Optional.of(result));
        when(results.saveAndFlush(result)).thenReturn(result);
        when(media.findByMissionIdOrderByCapturedAtDesc("m")).thenReturn(List.of());
        when(user.getCurrentUserId()).thenReturn("manager");
    }
    @Test void multiMissionApprovalUsesOrderFirstBoundaryThenReevaluatesAggregate() {
        service.approve("r", null);
        var sequence = inOrder(results, completion, checklist);
        sequence.verify(results).findOrderIdByResultId("r");
        sequence.verify(completion).lockAggregate("o"); sequence.verify(results).findById("r");
        sequence.verify(checklist).requireMaterialized(mission); sequence.verify(checklist).requireReadyForFinalApproval(mission);
        sequence.verify(results).saveAndFlush(result); sequence.verify(completion).completeIfReady(aggregate);
        assertEquals(MissionResultApprovalStatus.APPROVED, result.getApprovalStatus());
        assertEquals(OrderStatus.APPROVED, order.getOrderStatus());
        verifyNoInteractions(missions);
    }
    @Test void approvedReplayReevaluatesWithoutReplacingReviewAuditOrSavingResult() {
        result.setApprovalStatus(MissionResultApprovalStatus.APPROVED); result.setReviewedBy("original-manager");
        result.setReviewNote("Original review"); result.setApprovedAt(java.time.Instant.now());
        var approvedAt = result.getApprovedAt(); var request = new MissionResultReviewRequest(); request.setNote("Changed");
        service.approve("r", request);
        assertEquals(approvedAt, result.getApprovedAt()); assertEquals("original-manager", result.getReviewedBy());
        assertEquals("Original review", result.getReviewNote()); verify(results, never()).saveAndFlush(any());
        verify(completion).completeIfReady(aggregate); verify(user, never()).getCurrentUserId();
    }
    @Test void rejectedResultCannotApprove() {
        result.setApprovalStatus(MissionResultApprovalStatus.REJECTED);
        assertThrows(ApiException.class, () -> service.approve("r", null));
        verify(completion, never()).completeIfReady(any()); verify(results, never()).saveAndFlush(any());
    }
    @Test void readinessFailurePreventsApproval() {
        doThrow(new ApiException(ErrorCode.CHECKLIST_NOT_READY)).when(checklist).requireReadyForFinalApproval(mission);
        assertThrows(ApiException.class, () -> service.approve("r", null));
        assertEquals(MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL, result.getApprovalStatus());
        verify(completion, never()).completeIfReady(any()); verify(results, never()).saveAndFlush(any());
    }
    @Test void queryMappingDoesNotReplaceManagedResultMediaCollection() {
        var original = result.getMediaFiles(); when(results.findByMissionId("m")).thenReturn(Optional.of(result));
        service.getByMissionId("m"); assertSame(original, result.getMediaFiles());
        verifyNoInteractions(completion); verify(results, never()).saveAndFlush(any());
    }

    private void ordered(int images, int videos) {
        var deliverable = com.ondemandmonitoring.order.domain.OrderDeliverable.builder()
                .requirement(Map.of("imageQuantity", images, "videoQuantity", videos)).build();
        order.setDeliverables(new ArrayList<>(List.of(deliverable)));
    }
    private com.ondemandmonitoring.media.domain.MediaAsset asset(String id, String type, String checksum) {
        var a = new com.ondemandmonitoring.media.domain.MediaAsset();
        a.setId(id); a.setType(type); a.setChecksumSha256(checksum);
        a.setMediaStatus(com.ondemandmonitoring.media.enums.MediaStatus.AVAILABLE);
        return a;
    }
    @Test void shortfallAgainstTheOrderedMediaNeedsAWrittenReason() {
        ordered(10, 1);
        when(media.findByMissionIdOrderByCapturedAtDesc("m")).thenReturn(List.of(
                asset("a1", "IMAGE", "sha-1"), asset("a2", "IMAGE", "sha-1"), asset("a3", "IMAGE", "sha-2")));
        var ex = assertThrows(ApiException.class, () -> service.approve("r", null));
        assertEquals(ErrorCode.ORDER_MEDIA_QUANTITY_NOT_MET, ex.getErrorCode());
        assertEquals(MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL, result.getApprovalStatus());
    }
    @Test void shortfallIsAllowedWithAReason() {
        ordered(10, 1);
        var review = new MissionResultReviewRequest(); review.setNote("Khach dong y giao thieu");
        service.approve("r", review);
        assertEquals(MissionResultApprovalStatus.APPROVED, result.getApprovalStatus());
    }
    @Test void enoughDistinctFilesNeedNoReason() {
        ordered(2, 1);
        when(media.findByMissionIdOrderByCapturedAtDesc("m")).thenReturn(List.of(
                asset("a1", "IMAGE", "sha-1"), asset("a2", "IMAGE", "sha-2"), asset("v1", "VIDEO", "sha-3")));
        service.approve("r", null);
        assertEquals(MissionResultApprovalStatus.APPROVED, result.getApprovalStatus());
    }
    @Test void ordersWithoutPerTypeQuantitiesAreNotChecked() {
        order.setDeliverables(new ArrayList<>(List.of(com.ondemandmonitoring.order.domain.OrderDeliverable.builder()
                .requirement(Map.of("mediaType", "IMAGE", "quantity", 10)).build())));
        service.approve("r", null);
        assertEquals(MissionResultApprovalStatus.APPROVED, result.getApprovalStatus());
    }
}
