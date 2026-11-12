package com.ondemandmonitoring.delivery.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.delivery.domain.*;
import com.ondemandmonitoring.delivery.enums.*;
import com.ondemandmonitoring.delivery.repository.*;
import com.ondemandmonitoring.delivery.service.impl.*;
import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.enums.InvoiceStatus;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.service.impl.MissionPaymentMilestoneService;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.*;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.*;
import com.ondemandmonitoring.media.service.impl.MediaDerivativeService;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.impl.ChecklistEvidencePolicy;
import com.ondemandmonitoring.order.domain.*;
import com.ondemandmonitoring.order.enums.*;
import com.ondemandmonitoring.order.repository.*;
import com.ondemandmonitoring.order.service.IOrderExecutionCompletionPolicy;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class InspectorPublicationTest {
    final OrderDeliveryRepository deliveries = mock(OrderDeliveryRepository.class);
    final OrderRepository orders = mock(OrderRepository.class);
    final MissionRepository missions = mock(MissionRepository.class);
    final MissionResultRepository results = mock(MissionResultRepository.class);
    final InvoiceRepository invoices = mock(InvoiceRepository.class);
    final MediaAssetRepository media = mock(MediaAssetRepository.class);
    final IOrderExecutionCompletionPolicy completion = mock(IOrderExecutionCompletionPolicy.class);
    final ResultReviewRoundPublisher publisher = mock(ResultReviewRoundPublisher.class);
    final ResultReviewRoundRepository rounds = mock(ResultReviewRoundRepository.class);
    final ResultReviewRoundEvidenceRepository roundEvidence = mock(ResultReviewRoundEvidenceRepository.class);
    final ResultReviewRoundItemRepository roundItems = mock(ResultReviewRoundItemRepository.class);
    final MissionChecklistEvidenceRepository evidence = mock(MissionChecklistEvidenceRepository.class);
    final OrderChecklistItemRepository items = mock(OrderChecklistItemRepository.class);
    final ResultRevisionCaseRepository cases = mock(ResultRevisionCaseRepository.class);
    final ResultRevisionCaseItemRepository caseItems = mock(ResultRevisionCaseItemRepository.class);
    final DeliveryWorkflowServiceImpl service = new DeliveryWorkflowServiceImpl(deliveries, orders, missions,
            results, invoices, mock(MissionPaymentMilestoneService.class), media, mock(MediaDerivativeService.class), mock(IResultRetentionAccessService.class),
            mock(AuthenticatedUserResolver.class), completion, publisher, rounds, roundEvidence, roundItems,
            evidence, items, new ChecklistEvidencePolicy(), cases, caseItems);
    final Order order = new Order();
    final OrderDelivery delivery = new OrderDelivery();
    final OrderDelivery secondDelivery = new OrderDelivery();
    final DeliveryMissionScope scope = mock(DeliveryMissionScope.class);
    final Mission first = mission("first", MissionPurpose.ORIGINAL);
    final Mission second = mission("second", MissionPurpose.ORIGINAL);
    final OrderChecklistItem a = item("a");
    final OrderChecklistItem b = item("b");
    final List<Mission> children = new ArrayList<>();
    final List<MissionResult> packages = new ArrayList<>();

    @BeforeEach void setUp() {
        order.setId("order"); order.setOrderStatus(OrderStatus.COMPLETED);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "missionScope", scope);
        delivery.setMissionId("first");
        secondDelivery.setId("delivery-second"); secondDelivery.setMissionId("second"); secondDelivery.setOrder(order);
        secondDelivery.setStatus(DeliveryStatus.PROCESSING); secondDelivery.setVersion(1L);
        when(scope.find("order", "first", true)).thenReturn(Optional.of(delivery));
        when(scope.find("order", "second", true)).thenReturn(Optional.of(secondDelivery));
        when(scope.checklistIds("order", "first")).thenReturn(Set.of("a"));
        when(scope.checklistIds("order", "second")).thenReturn(Set.of("b"));
        when(scope.missions("order", "first")).thenAnswer(call -> children.stream().filter(m -> m.getId().equals("first") || m.getPurpose() == MissionPurpose.REWORK).toList());
        when(scope.missions("order", "second")).thenAnswer(call -> children.stream().filter(m -> m.getId().equals("second") || m.getPurpose() == MissionPurpose.REWORK).toList());
        delivery.setId("delivery"); delivery.setOrder(order); delivery.setStatus(DeliveryStatus.PROCESSING); delivery.setVersion(1L);
        children.addAll(List.of(first, second)); packages.addAll(List.of(approved(first), approved(second)));
        when(completion.lockAggregate("order")).thenAnswer(c -> new IOrderExecutionCompletionPolicy.LockedExecution(order, List.copyOf(children)));
        when(orders.findByIdForUpdate("order")).thenReturn(Optional.of(order));
        when(missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc("order")).thenReturn(children);
        when(results.findByMissionIds(anyCollection())).thenAnswer(c -> {
            Collection<String> ids = c.getArgument(0);
            return packages.stream().filter(r -> ids.contains(r.getMission().getId())).toList();
        });
        when(deliveries.findByOrderIdForUpdate("order")).thenReturn(Optional.of(delivery));
        when(items.findAllByOrderIdOrderByDisplayOrderAscIdAsc("order")).thenReturn(List.of(a,b));
        when(evidence.findActiveByMissionId("first")).thenReturn(List.of(link("a1", first, a, true), link("reserve", first, a, false)));
        when(evidence.findActiveByMissionId("second")).thenReturn(List.of(link("b1", second, b, true)));
        var next = new ResultReviewRound(); next.setId("next"); next.setVersion(0L);
        when(publisher.publishByChecklist(any(), any(), anyList(), anyMap(), any(), any(), anyMap(), anyMap(), anySet(), anyBoolean())).thenReturn(next);
        var invoice = new Invoice(); invoice.setStatus(InvoiceStatus.PAID);
        when(invoices.findByOrderId("order")).thenReturn(Optional.of(invoice));
    }

    @Test void publishesCompletedMissionWithoutWaitingForOtherMissions() {
        second.setStatus(MissionStatus.IN_PROGRESS);
        assertThat(service.publishInspectedResults("order")).isTrue();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.CUSTOMER_REVIEW);
        assertThat(secondDelivery.getStatus()).isEqualTo(DeliveryStatus.PROCESSING);
        verify(publisher).publishByChecklist(eq(order), eq(delivery), anyList(), eq(Map.of("a", Set.of("a1"))), any(), any(), anyMap(), anyMap(), eq(Set.of("a")), eq(true));
    }

    @Test void publishesOnlySelectedMediaAndReplayDoesNotCreateAnotherRound() {
        assertThat(service.publishInspectedResults("order")).isTrue();
        ArgumentCaptor<Map<String,Set<String>>> selections = ArgumentCaptor.forClass(Map.class);
        verify(publisher).publishByChecklist(eq(order), eq(delivery), anyList(), selections.capture(), any(), any(), anyMap(), anyMap(), eq(Set.of("a")), eq(true));
        assertThat(selections.getValue()).containsOnlyKeys("a").containsEntry("a", Set.of("a1"));
        verify(publisher).publishByChecklist(eq(order), eq(secondDelivery), anyList(), eq(Map.of("b", Set.of("b1"))), any(), any(), anyMap(), anyMap(), eq(Set.of("b")), eq(true));
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.CUSTOMER_REVIEW);
        assertThat(order.getResultExpiresAt()).isNull();
        assertThat(service.publishInspectedResults("order")).isFalse();
        verifyNoMoreInteractions(publisher);
    }

    @Test void publicationCapturesTheVersionAfterItsOwnDeliveryTransitionIsFlushed() {
        // Emulate JPA AUTO flush: a query persists a dirty delivery and increments @Version.
        var storedStatus = new java.util.concurrent.atomic.AtomicReference<>(delivery.getStatus());
        Runnable flush = () -> {
            if (storedStatus.get() != delivery.getStatus()) {
                delivery.setVersion(delivery.getVersion() + 1);
                storedStatus.set(delivery.getStatus());
            }
        };
        doAnswer(call -> { flush.run(); return null; }).when(deliveries).flush();
        when(orders.findByIdForUpdate("order")).thenAnswer(call -> {
            flush.run();
            return Optional.of(order);
        });

        assertThat(service.publishInspectedResults("order")).isTrue();
        assertThat(delivery.getVersion()).isGreaterThanOrEqualTo(2L);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.CUSTOMER_REVIEW);
        verify(publisher).publishByChecklist(eq(order), eq(delivery), anyList(), anyMap(), any(), any(),
                anyMap(), anyMap(), eq(Set.of("a")), eq(true));
    }

    @Test void missingSelectionBlocksPublicationDespiteReserveImages() {
        when(evidence.findActiveByMissionId("first")).thenReturn(List.of(link("reserve", first, a, false)));
        assertThatThrownBy(() -> service.publishInspectedResults("order")).isInstanceOf(com.ondemandmonitoring.common.exception.ApiException.class);
        verifyNoInteractions(publisher);
    }

    @Test void orderQuantityDoesNotDelayAnIndividuallyReadyMission() {
        var requirement = new OrderDeliverable(); requirement.setRequirement(Map.of("imageQuantity",3));
        order.getDeliverables().add(requirement);
        second.setStatus(MissionStatus.IN_PROGRESS);
        assertThat(service.publishInspectedResults("order")).isTrue();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.CUSTOMER_REVIEW);
    }

    @Test void pendingReserveMediaCaseRemainsUnderManagerControl() {
        var revisionCase = new ResultRevisionCase(); revisionCase.setId("case"); revisionCase.setOrder(order);
        when(cases.findByOrderIdAndActiveTrueForUpdate("order")).thenReturn(Optional.of(revisionCase));
        var item = new ResultRevisionCaseItem(); item.setOrderChecklistItem(a);
        item.setScopeDecision(ResultRevisionScope.IN_SCOPE); item.setStrategy(ResultRevisionStrategy.RESERVE_MEDIA);
        item.setStatus(ResultRevisionCaseItemStatus.IN_PROGRESS);
        when(caseItems.findByResultRevisionCaseIdOrderByCreatedAtAsc("case")).thenReturn(List.of(item));
        delivery.setCurrentReviewRoundId("old"); delivery.setStatus(DeliveryStatus.REVISION_REQUESTED);
        assertThat(service.publishInspectedResults("order")).isTrue();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.REVISION_REQUESTED);
        assertThat(secondDelivery.getStatus()).isEqualTo(DeliveryStatus.CUSTOMER_REVIEW);
    }

    @ParameterizedTest @ValueSource(booleans = {false,true})
    void reworkReplacesOnlyItsChecklistAndRetainsUnchangedChecklist(boolean sharedMedia) {
        delivery.setMissionId(null);
        when(deliveries.findByOrderId("order")).thenReturn(Optional.of(delivery));
        if (sharedMedia) {
            when(evidence.findActiveByMissionId("first")).thenReturn(List.of(link("a1",first,a,true),link("a1",first,b,true)));
            when(evidence.findActiveByMissionId("second")).thenReturn(List.of());
        }
        var revisionCase = new ResultRevisionCase(); revisionCase.setId("case"); revisionCase.setOrder(order);
        when(cases.findByOrderIdAndActiveTrueForUpdate("order")).thenReturn(Optional.of(revisionCase));
        var rework = mission("rework", MissionPurpose.REWORK); rework.setResultRevisionCaseId("case");
        children.add(rework); packages.add(approved(rework));
        when(missions.findById("rework")).thenReturn(Optional.of(rework));
        when(evidence.findActiveByMissionId("rework")).thenReturn(List.of(link("new-b", rework, b, true)));
        var item = new ResultRevisionCaseItem(); item.setOrderChecklistItem(b);
        item.setScopeDecision(ResultRevisionScope.IN_SCOPE); item.setStrategy(ResultRevisionStrategy.REWORK);
        item.setStatus(ResultRevisionCaseItemStatus.IN_PROGRESS); item.setAssignedReworkMissionId("rework");
        when(caseItems.findByResultRevisionCaseIdOrderByCreatedAtAsc("case")).thenReturn(List.of(item));
        when(caseItems.findByResultRevisionCaseIdAndOrderChecklistItemIdInOrderByCreatedAtAsc(eq("case"), anyCollection())).thenReturn(List.of(item));
        delivery.setCurrentReviewRoundId("old"); delivery.setStatus(DeliveryStatus.REVISION_REQUESTED);
        var old = new ResultReviewRound(); old.setId("old"); old.setVersion(2L);
        when(rounds.findByIdForUpdate("old")).thenReturn(Optional.of(old));
        var riA = new ResultReviewRoundItem(); riA.setId("ri-a"); riA.setOrderChecklistItem(a);
        var riB = new ResultReviewRoundItem(); riB.setId("ri-b"); riB.setOrderChecklistItem(b);
        when(roundItems.findByRoundIdOrderByDisplayOrderAsc("old")).thenReturn(List.of(riA,riB));
        var ea = new ResultReviewRoundEvidence(); ea.setId("old-a"); ea.setRoundItem(riA); ea.setMediaAsset(link("a1",first,a,true).getMediaAsset());
        var eb = new ResultReviewRoundEvidence(); eb.setId("old-b"); eb.setRoundItem(riB); eb.setMediaAsset(link(sharedMedia ? "a1" : "b1",sharedMedia ? first : second,b,true).getMediaAsset());
        when(roundEvidence.findByRoundItemIdInOrderByRoundItemIdAscCreatedAtAsc(List.of("ri-a","ri-b"))).thenReturn(List.of(ea,eb));
        assertThat(service.publishInspectedResults("order")).isTrue();
        ArgumentCaptor<Map<String,Set<String>>> selections = ArgumentCaptor.forClass(Map.class);
        verify(publisher).publishByChecklist(eq(order), eq(delivery), anyList(), selections.capture(), any(), any(), anyMap(), anyMap(), eq(Set.of("b")), eq(false));
        assertThat(selections.getValue()).containsEntry("a",Set.of("a1")).containsEntry("b",Set.of("new-b"));
        assertThat(item.getStatus()).isEqualTo(ResultRevisionCaseItemStatus.WAITING_CUSTOMER);
    }

    private Mission mission(String id, MissionPurpose purpose) {
        var m = new Mission(); m.setId(id); m.setOrder(order); m.setStatus(MissionStatus.COMPLETED); m.setPurpose(purpose); return m;
    }
    private MissionResult approved(Mission mission) {
        var r = new MissionResult(); r.setMission(mission); r.setApprovalStatus(MissionResultApprovalStatus.APPROVED); r.setStatus(MissionResultStatus.COMPLETED); r.setInspectorConfirmed(true); return r;
    }
    private OrderChecklistItem item(String id) {
        var item = new OrderChecklistItem(); item.setId(id); item.setOrder(order); item.setMinimumImageCount(1);
        item.setRequiredEvidenceType(RequiredEvidenceType.IMAGE); return item;
    }
    private MissionChecklistEvidence link(String id, Mission mission, OrderChecklistItem item, boolean selected) {
        var asset = new MediaAsset(); asset.setId(id); asset.setMission(mission); asset.setType("IMAGE");
        asset.setStorageStatus(MediaStorageStatus.AVAILABLE); asset.setMediaStatus(MediaStatus.AVAILABLE);
        asset.setValidatedAt(Instant.now()); asset.setSourceType("DRONE_CAMERA");
        var execution = new MissionChecklistExecution(); execution.setOrderChecklistItemId(item.getId());
        var link = new MissionChecklistEvidence(); link.setMissionId(mission.getId()); link.setMissionChecklistExecution(execution);
        link.setMediaAsset(asset); link.setSelectedForCustomer(selected); return link;
    }
}
