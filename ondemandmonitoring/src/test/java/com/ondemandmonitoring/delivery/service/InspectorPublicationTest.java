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
    final com.ondemandmonitoring.mission.inspection.service.impl.InspectionCorrectionPolicy corrections =
            mock(com.ondemandmonitoring.mission.inspection.service.impl.InspectionCorrectionPolicy.class);
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
        org.springframework.test.util.ReflectionTestUtils.setField(service, "inspectionCorrections", corrections);
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

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void reworkReplacesOnlyItsChecklistAndRetainsUnchangedChecklist(boolean sharedMedia, boolean supplementalFlight) {
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
        if (supplementalFlight) {
            var supplement = mission("supplement", MissionPurpose.REWORK);
            children.add(supplement); packages.add(approved(supplement));
            var acceptedLink = link("new-b", supplement, b, true);
            when(evidence.findActiveByMissionId("rework")).thenReturn(List.of(link("rejected-b", rework, b, false)));
            when(evidence.findActiveByMissionId("supplement")).thenReturn(List.of(acceptedLink));
            when(corrections.selectedSupplementalEvidence("rework")).thenReturn(List.of(acceptedLink));
        }
        assertThat(service.publishInspectedResults("order")).isTrue();
        ArgumentCaptor<Map<String,Set<String>>> selections = ArgumentCaptor.forClass(Map.class);
        verify(publisher).publishByChecklist(eq(order), eq(delivery), anyList(), selections.capture(), any(), any(), anyMap(), anyMap(), eq(Set.of("b")), eq(false));
        assertThat(selections.getValue()).containsEntry("a",Set.of("a1")).containsEntry("b",Set.of("new-b"));
        assertThat(item.getStatus()).isEqualTo(ResultRevisionCaseItemStatus.WAITING_CUSTOMER);
    }

    @Test
    void queryAndManualPublishExcludeNonCompliantAndDelegatedEvidenceWhileAllowingReworkSupplementsAndReplacingIneligiblePriorFiles() {
        var storage = mock(IMediaObjectStorage.class);
        when(storage.presignedUrlExpiresSeconds()).thenReturn(900L);
        when(storage.createPresignedGetUrl(any(), any())).thenReturn("https://signed.example/get");
        var queryService = new ChecklistDeliveryQueryService(
                orders, cases, caseItems, items, missions, results, evidence, deliveries, rounds, roundItems,
                roundEvidence, new ChecklistEvidencePolicy(), mock(IResultRetentionAccessService.class),
                storage, mock(MediaDerivativeService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(queryService, "missionScope", scope);
        org.springframework.test.util.ReflectionTestUtils.setField(queryService, "inspectionCorrections", corrections);

        when(orders.findById("order")).thenReturn(Optional.of(order));
        when(scope.find("order", "first", false)).thenReturn(Optional.of(delivery));

        // Checklist 'a' on mission 'first':
        // - 'bad-non-compliant' has NON_COMPLIANT assessment
        // - 'delegated-old' has COMPLIANT assessment but its execution is in delegatedExecutions
        // - 'supp-valid' comes from accepted supplemental mission 'supp-a'
        var badLink = link("bad-non-compliant", first, a, false);
        badLink.getMissionChecklistExecution().setId("exec-bad");
        badLink.getMissionChecklistExecution().setAssessmentStatus(ChecklistAssessmentStatus.NON_COMPLIANT);

        var delegatedLink = link("delegated-old", first, a, false);
        delegatedLink.getMissionChecklistExecution().setId("exec-delegated");

        var suppA = mission("supp-a", MissionPurpose.REWORK);
        suppA.setMissionCode("MS-SUPP-A");
        children.add(suppA);
        packages.add(approved(suppA));
        var suppValidLink = link("supp-valid", suppA, a, true);
        var suppReserveLink = link("supp-reserve", suppA, a, false);

        when(evidence.findActiveByMissionId("first")).thenReturn(List.of(badLink, delegatedLink));
        when(evidence.findActiveByMissionId("supp-a")).thenReturn(List.of(suppValidLink, suppReserveLink));
        when(corrections.delegatedExecutions("first")).thenReturn(Map.of("exec-delegated", delegatedLink.getMissionChecklistExecution()));

        var queryResult = queryService.get("order", "first");
        assertThat(queryResult.checklists()).hasSize(1);
        assertThat(queryResult.checklists().getFirst().eligibleMedia())
                .extracting(com.ondemandmonitoring.delivery.dto.response.ManagerDeliveryChecklistsResponse.Asset::mediaAssetId)
                .containsExactlyInAnyOrder("supp-valid", "supp-reserve")
                .doesNotContain("bad-non-compliant", "delegated-old");

        // Now set up a Round 1 delivery containing an old file 'expired-old' that is no longer eligible,
        // and an active revision case on checklist 'a' with REWORK mission 'rework-a' supplemented by 'supp-rework-a'.
        delivery.setStatus(DeliveryStatus.REVISION_REQUESTED);
        delivery.setCurrentReviewRoundId("round-1");
        var round1 = new ResultReviewRound();
        round1.setId("round-1");
        round1.setVersion(1L);
        when(rounds.findById("round-1")).thenReturn(Optional.of(round1));
        when(rounds.findByIdForUpdate("round-1")).thenReturn(Optional.of(round1));

        var riA = new ResultReviewRoundItem();
        riA.setId("ri-a");
        riA.setOrderChecklistItem(a);
        when(roundItems.findByRoundIdOrderByDisplayOrderAsc("round-1")).thenReturn(List.of(riA));

        var priorIneligibleAsset = new MediaAsset();
        priorIneligibleAsset.setId("expired-old");
        priorIneligibleAsset.setMission(first);
        priorIneligibleAsset.setType("IMAGE");
        var priorRoundEvidence = new ResultReviewRoundEvidence();
        priorRoundEvidence.setId("re-old");
        priorRoundEvidence.setRoundItem(riA);
        priorRoundEvidence.setMediaAsset(priorIneligibleAsset);
        when(roundEvidence.findByRoundItemIdInOrderByRoundItemIdAscCreatedAtAsc(List.of("ri-a")))
                .thenReturn(List.of(priorRoundEvidence));

        var revisionCase = new ResultRevisionCase();
        revisionCase.setId("case-1");
        revisionCase.setOrder(order);
        when(cases.findByOrderIdAndActiveTrue("order")).thenReturn(Optional.of(revisionCase));
        when(cases.findByOrderIdAndActiveTrueForUpdate("order")).thenReturn(Optional.of(revisionCase));

        var reworkA = mission("rework-a", MissionPurpose.REWORK);
        reworkA.setResultRevisionCaseId("case-1");
        reworkA.setMissionCode("MS-RW-A");
        var suppReworkA = mission("supp-rework-a", MissionPurpose.REWORK);
        suppReworkA.setMissionCode("MS-SUPP-RW-A");
        children.addAll(List.of(reworkA, suppReworkA));
        packages.addAll(List.of(approved(reworkA), approved(suppReworkA)));
        when(missions.findById("rework-a")).thenReturn(Optional.of(reworkA));

        var reworkCaseItem = new ResultRevisionCaseItem();
        reworkCaseItem.setOrderChecklistItem(a);
        reworkCaseItem.setScopeDecision(ResultRevisionScope.IN_SCOPE);
        reworkCaseItem.setStrategy(ResultRevisionStrategy.REWORK);
        reworkCaseItem.setStatus(ResultRevisionCaseItemStatus.IN_PROGRESS);
        reworkCaseItem.setAssignedReworkMissionId("rework-a");
        when(caseItems.findByResultRevisionCaseIdOrderByCreatedAtAsc("case-1")).thenReturn(List.of(reworkCaseItem));
        when(caseItems.findByResultRevisionCaseIdAndOrderChecklistItemIdInOrderByCreatedAtAsc(eq("case-1"), anyCollection()))
                .thenReturn(List.of(reworkCaseItem));

        var reworkFailedLink = link("rework-failed", reworkA, a, false);
        reworkFailedLink.getMissionChecklistExecution().setId("exec-rw-failed");
        reworkFailedLink.getMissionChecklistExecution().setAssessmentStatus(ChecklistAssessmentStatus.NON_COMPLIANT);
        var reworkSuppAcceptedLink = link("rework-supp-accepted", suppReworkA, a, true);

        when(evidence.findActiveByMissionId("rework-a")).thenReturn(List.of(reworkFailedLink));
        when(evidence.findActiveByMissionId("supp-rework-a")).thenReturn(List.of(reworkSuppAcceptedLink));
        when(corrections.delegatedExecutions("rework-a")).thenReturn(Map.of("exec-rw-failed", reworkFailedLink.getMissionChecklistExecution()));
        when(corrections.selectedSupplementalEvidence("rework-a")).thenReturn(List.of(reworkSuppAcceptedLink));

        // 1) Query service returns ONLY the accepted supplemental evidence for the rework checklist
        var reworkQuery = queryService.get("order", "first");
        assertThat(reworkQuery.checklists().getFirst().eligibleMedia())
                .extracting(com.ondemandmonitoring.delivery.dto.response.ManagerDeliveryChecklistsResponse.Asset::mediaAssetId)
                .containsExactly("rework-supp-accepted");

        // 2) Direct publish attempt with NON_COMPLIANT or pre-rework media is rejected with MEDIA_ACCESS_DENIED
        var rejectBadAttempt = new com.ondemandmonitoring.delivery.dto.request.ChecklistDeliveryPublishRequest(
                UUID.randomUUID(), 1L, "round-1", 1L, "Try bad media", false, null,
                List.of(new com.ondemandmonitoring.delivery.dto.request.ChecklistDeliveryPublishRequest.ChecklistSelection(
                        "a", List.of(), List.of(),
                        List.of(new com.ondemandmonitoring.delivery.dto.request.ChecklistDeliveryPublishRequest.Replacement("re-old", "bad-non-compliant")))));
        assertThatThrownBy(() -> service.releaseChecklistPreview("order", rejectBadAttempt, "first"))
                .isInstanceOf(com.ondemandmonitoring.common.exception.ApiException.class)
                .extracting("errorCode")
                .isEqualTo(com.ondemandmonitoring.common.exception.ErrorCode.MEDIA_ACCESS_DENIED);

        // 3) Replacing the no-longer-eligible prior file 're-old' ('expired-old') with 'rework-supp-accepted' succeeds!
        var validReplace = new com.ondemandmonitoring.delivery.dto.request.ChecklistDeliveryPublishRequest(
                UUID.randomUUID(), 1L, "round-1", 1L, "Replace with accepted rework supplement", false, null,
                List.of(new com.ondemandmonitoring.delivery.dto.request.ChecklistDeliveryPublishRequest.ChecklistSelection(
                        "a", List.of(), List.of(),
                        List.of(new com.ondemandmonitoring.delivery.dto.request.ChecklistDeliveryPublishRequest.Replacement("re-old", "rework-supp-accepted")))));
        var published = service.releaseChecklistPreview("order", validReplace, "first");
        assertThat(published.deliveryStatus()).isEqualTo(DeliveryStatus.CUSTOMER_REVIEW);
        assertThat(reworkCaseItem.getStatus()).isEqualTo(ResultRevisionCaseItemStatus.WAITING_CUSTOMER);
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
        var execution = new MissionChecklistExecution();
        execution.setId(mission.getId() + "-" + item.getId());
        execution.setOrderChecklistItemId(item.getId());
        execution.setExecutionStatus(ChecklistExecutionStatus.COMPLETED);
        execution.setAssessmentStatus(ChecklistAssessmentStatus.COMPLIANT);
        var link = new MissionChecklistEvidence(); link.setMissionId(mission.getId()); link.setMissionChecklistExecution(execution);
        link.setMediaAsset(asset); link.setSelectedForCustomer(selected); return link;
    }
}
