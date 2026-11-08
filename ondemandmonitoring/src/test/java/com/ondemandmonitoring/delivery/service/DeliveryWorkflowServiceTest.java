package com.ondemandmonitoring.delivery.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.delivery.domain.OrderDelivery;
import com.ondemandmonitoring.delivery.dto.request.ReleasePreviewRequest;
import com.ondemandmonitoring.delivery.dto.request.RevisionRequest;
import com.ondemandmonitoring.delivery.enums.DeliveryStatus;
import com.ondemandmonitoring.delivery.repository.OrderDeliveryRepository;
import com.ondemandmonitoring.delivery.domain.ResultReviewRound;
import com.ondemandmonitoring.delivery.domain.ResultReviewRoundItem;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.delivery.repository.ResultReviewRoundRepository;
import com.ondemandmonitoring.delivery.repository.ResultReviewRoundEvidenceRepository;
import com.ondemandmonitoring.delivery.repository.ResultReviewRoundItemRepository;
import com.ondemandmonitoring.delivery.repository.ResultRevisionCaseRepository;
import com.ondemandmonitoring.delivery.repository.ResultRevisionCaseItemRepository;
import com.ondemandmonitoring.delivery.service.impl.ResultReviewRoundPublisher;
import com.ondemandmonitoring.delivery.service.impl.DeliveryWorkflowServiceImpl;
import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.domain.Quote;
import com.ondemandmonitoring.finance.enums.InvoiceStatus;
import com.ondemandmonitoring.finance.enums.QuoteStatus;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.enums.MissionResultApprovalStatus;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.repository.MissionResultRepository;
import com.ondemandmonitoring.mission.repository.MissionChecklistEvidenceRepository;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.repository.OrderChecklistItemRepository;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.mission.service.impl.ChecklistEvidencePolicy;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeliveryWorkflowServiceTest {
    OrderDeliveryRepository deliveries = mock(OrderDeliveryRepository.class);
    OrderRepository orders = mock(OrderRepository.class);
    MissionRepository missions = mock(MissionRepository.class);
    MissionResultRepository missionResults = mock(MissionResultRepository.class);
    InvoiceRepository invoices = mock(InvoiceRepository.class);
    MediaAssetRepository media = mock(MediaAssetRepository.class);
    AuthenticatedUserResolver users = mock(AuthenticatedUserResolver.class);
    com.ondemandmonitoring.order.service.IOrderExecutionCompletionPolicy completion = mock(com.ondemandmonitoring.order.service.IOrderExecutionCompletionPolicy.class);
    ResultReviewRoundPublisher reviewPublisher = mock(ResultReviewRoundPublisher.class);
    ResultReviewRoundRepository reviewRounds = mock(ResultReviewRoundRepository.class);
    ResultReviewRoundEvidenceRepository roundEvidence = mock(ResultReviewRoundEvidenceRepository.class);
    ResultReviewRoundItemRepository roundItems = mock(ResultReviewRoundItemRepository.class);
    MissionChecklistEvidenceRepository missionEvidence = mock(MissionChecklistEvidenceRepository.class);
    OrderChecklistItemRepository checklistItems = mock(OrderChecklistItemRepository.class);
    ChecklistEvidencePolicy evidencePolicy = mock(ChecklistEvidencePolicy.class);
    ResultRevisionCaseRepository revisionCases = mock(ResultRevisionCaseRepository.class);
    ResultRevisionCaseItemRepository revisionCaseItems = mock(ResultRevisionCaseItemRepository.class);
    DeliveryWorkflowServiceImpl service = new DeliveryWorkflowServiceImpl(
            deliveries, orders, missions, missionResults, invoices, media,
            mock(com.ondemandmonitoring.media.service.impl.MediaDerivativeService.class),
            new com.ondemandmonitoring.media.service.impl.ResultRetentionAccessServiceImpl(media, java.time.Clock.systemUTC()),
            users, completion, reviewPublisher, reviewRounds, roundEvidence, roundItems, missionEvidence,
            checklistItems, evidencePolicy, revisionCases, revisionCaseItems);
    User customer; Order order; Mission mission; Invoice invoice; OrderDelivery delivery;

    @BeforeEach void setUp() {
        customer = user("customer", RoleCode.CUSTOMER);
        order = new Order(); order.setId("order"); order.setCustomer(customer); order.setOrderStatus(OrderStatus.IN_PROGRESS);
        order.setDataRetentionDays(90);
        mission = new Mission(); mission.setId("mission"); mission.setOrder(order); mission.setStatus(MissionStatus.COMPLETED);
        Quote quote = new Quote(); quote.setStatus(QuoteStatus.ACCEPTED_BY_CUSTOMER);
        invoice = new Invoice(); invoice.setOrder(order); invoice.setQuote(quote); invoice.setStatus(InvoiceStatus.PARTIALLY_PAID);
        invoice.setTotalAmount(new BigDecimal("8500000")); invoice.setPaidAmount(new BigDecimal("2550000"));
        invoice.setRemainingAmount(new BigDecimal("5950000"));
        delivery = new OrderDelivery(); delivery.setOrder(order); delivery.setStatus(DeliveryStatus.CUSTOMER_REVIEW);
        delivery.setCurrentReviewRoundId("round");
        ResultReviewRound round = new ResultReviewRound(); round.setId("round"); round.setVersion(2L);
        when(reviewRounds.findByIdForUpdate("round")).thenReturn(Optional.of(round));
        when(reviewRounds.findById("round")).thenReturn(Optional.of(round));
        when(users.getCurrentUser()).thenReturn(customer); when(users.getCurrentUserId()).thenReturn(customer.getId());
        when(orders.findById("order")).thenReturn(Optional.of(order));
        when(orders.findByIdForUpdate("order")).thenReturn(Optional.of(order));
        when(missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc("order")).thenReturn(List.of(mission));
        var result = new com.ondemandmonitoring.mission.domain.MissionResult(); result.setMission(mission);
        result.setApprovalStatus(MissionResultApprovalStatus.APPROVED);
        when(missionResults.findByMissionIds(List.of("mission"))).thenReturn(List.of(result));
        when(completion.lockAggregate("order")).thenReturn(new com.ondemandmonitoring.order.service.IOrderExecutionCompletionPolicy.LockedExecution(order, List.of(mission)));
        when(completion.completeIfReady(any())).thenAnswer(call -> { order.setOrderStatus(OrderStatus.COMPLETED); return true; });
        when(invoices.findByOrderId("order")).thenReturn(Optional.of(invoice));
        when(deliveries.findByOrderIdForUpdate("order")).thenReturn(Optional.of(delivery));
        when(deliveries.findByOrderId("order")).thenReturn(Optional.of(delivery));
        when(deliveries.save(any(OrderDelivery.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(media.findByMissionIdOrderByCapturedAtDesc("mission")).thenReturn(List.of(asset("original", MediaStatus.AVAILABLE)));
        when(missions.findAllById(List.of("mission"))).thenReturn(List.of(mission));
        when(roundEvidence.findCurrentPublishedMediaAssetIdsByOrderIds(List.of("order"))).thenReturn(Set.of("original"));
    }

    @Test void legacyUnpaidAcceptanceRemainsPendingReconciliation() {
        var result = service.acceptResult("order", "round", 2L);
        assertThat(result.deliveryStatus()).isEqualTo(DeliveryStatus.FINAL_PAYMENT_PENDING);
        assertThat(delivery.getCustomerAcceptedAt()).isNotNull();
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.IN_PROGRESS);
    }

    @Test void paidOrderAcceptanceMovesDirectlyToDeliveryAndRetryDoesNotReopenPayment() {
        invoice.setStatus(InvoiceStatus.PAID);
        invoice.setPaidAmount(invoice.getTotalAmount());
        invoice.setRemainingAmount(BigDecimal.ZERO);

        var accepted = service.acceptResult("order", "round", 2L);
        var acceptedAt = delivery.getCustomerAcceptedAt();
        var round = reviewRounds.findByIdForUpdate("round").orElseThrow();
        round.setVersion(3L);
        var retry = service.acceptResult("order", "round", 2L);

        assertThat(accepted.deliveryStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(retry.deliveryStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(delivery.getCustomerAcceptedAt()).isEqualTo(acceptedAt);
        assertThat(round.getStatus()).isEqualTo(com.ondemandmonitoring.delivery.enums.ResultReviewRoundStatus.ACCEPTED);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(delivery.getFinalPaymentConfirmedAt()).isNull();
    }

    @Test void acceptedRoundRetryAfterHandoffKeepsDeliveryComplete() {
        service.acceptResult("order", "round", 2L);
        delivery.setStatus(DeliveryStatus.DELIVERED);
        reviewRounds.findByIdForUpdate("round").orElseThrow().setVersion(3L);

        assertThat(service.acceptResult("order", "round", 2L).deliveryStatus())
                .isEqualTo(DeliveryStatus.DELIVERED);
    }

    @Test void paidLegacyAcceptanceRetryAutomaticallyReleasesOriginals() {
        service.acceptResult("order", "round", 2L);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FINAL_PAYMENT_PENDING);
        assertThat(order.getResultExpiresAt()).isNull();
        invoice.setStatus(InvoiceStatus.PAID); invoice.setRemainingAmount(BigDecimal.ZERO);
        var released = service.acceptResult("order", "round", 2L);
        assertThat(released.deliveryStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(order.getResultExpiresAt()).isEqualTo(delivery.getOriginalsReleasedAt().plus(java.time.Duration.ofDays(90)));
    }

    @Test void staleRoundCannotAcceptResultsEvenWhenInvoiceIsPaid() {
        invoice.setStatus(InvoiceStatus.PAID);
        invoice.setRemainingAmount(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.acceptResult("order", "round", 1L))
                .isInstanceOf(ApiException.class).extracting("errorCode").isEqualTo(ErrorCode.RESULT_REVIEW_REFRESH_REQUIRED);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.CUSTOMER_REVIEW);
        assertThat(delivery.getCustomerAcceptedAt()).isNull();
    }

    @Test void acceptanceRetryWithOriginalRoundVersionIsIdempotentAfterVersionIncrement() {
        service.acceptResult("order", "round", 2L);
        delivery.setStatus(DeliveryStatus.FINAL_PAYMENT_PENDING);
        var acceptedAt = delivery.getCustomerAcceptedAt();
        var round = reviewRounds.findByIdForUpdate("round").orElseThrow();
        round.setVersion(3L);

        var retry = service.acceptResult("order", "round", 2L);

        assertThat(retry.deliveryStatus()).isEqualTo(DeliveryStatus.FINAL_PAYMENT_PENDING);
        assertThat(delivery.getCustomerAcceptedAt()).isEqualTo(acceptedAt);
    }


    @Test void legacyRevisionPayloadRequiresRefreshingTheResultReview() {
        assertThatThrownBy(() -> service.requestRevision("order", new RevisionRequest(null, null, null, null, List.of())))
                .isInstanceOf(ApiException.class).extracting("errorCode").isEqualTo(ErrorCode.RESULT_REVIEW_REFRESH_REQUIRED);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.CUSTOMER_REVIEW);
        assertThat(delivery.getRevisionCount()).isZero();
    }

    @Test void managerSelectsApprovedPreviewAssets() {
        delivery.setStatus(DeliveryStatus.READY_FOR_MANAGER_REVIEW);
        User manager = user("manager", RoleCode.MANAGER); when(users.getCurrentUser()).thenReturn(manager);
        MediaAsset selected = asset("selected", MediaStatus.AVAILABLE);
        MediaAsset excluded = asset("excluded", MediaStatus.AVAILABLE);
        when(media.findByMissionIdOrderByCapturedAtDesc("mission")).thenReturn(List.of(selected, excluded));
        when(reviewPublisher.publish(any(), any(), anyList(), anySet(), any(), any())).thenAnswer(call -> {
            var round = new ResultReviewRound(); round.setId("round-new"); return round;
        });
        service.releasePreview("order", new ReleasePreviewRequest(List.of("selected"), "QA passed"));
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.CUSTOMER_REVIEW);
        assertThat(selected.isPreviewSelected()).isTrue(); assertThat(excluded.isPreviewSelected()).isFalse();
    }

    @Test void managerCannotReleasePreviewBeforeResultApproval() {
        delivery.setStatus(DeliveryStatus.READY_FOR_MANAGER_REVIEW);
        when(missionResults.findByMissionIds(List.of("mission"))).thenReturn(List.of());

        assertThatThrownBy(() -> service.releasePreview("order",
                new ReleasePreviewRequest(List.of("selected"), null)))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DELIVERY_NOT_READY);
    }

    @Test void legacyDeliveryIsInitializedByValidatedReleaseCommandNotByGet() {
        when(deliveries.findByOrderIdForUpdate("order")).thenReturn(Optional.empty());
        when(users.getCurrentUser()).thenReturn(user("manager", RoleCode.MANAGER));
        when(media.findByMissionIdOrderByCapturedAtDesc("mission")).thenReturn(List.of(asset("selected", MediaStatus.AVAILABLE)));
        when(reviewPublisher.publish(any(), any(), anyList(), anySet(), any(), any())).thenAnswer(call -> {
            var round = new ResultReviewRound(); round.setId("round-new"); return round;
        });
        var response = service.releasePreview("order", new ReleasePreviewRequest(List.of("selected"), null));
        assertThat(response.deliveryStatus()).isEqualTo(DeliveryStatus.CUSTOMER_REVIEW);
        verify(deliveries).save(any(OrderDelivery.class));
        verify(completion).completeIfReady(any());
    }

    @Test void legacyCustomerReviewWithoutRoundCanBeRepublishedByManager() {
        delivery.setStatus(DeliveryStatus.CUSTOMER_REVIEW);
        delivery.setCurrentReviewRoundId(null);
        when(users.getCurrentUser()).thenReturn(user("manager", RoleCode.MANAGER));
        when(media.findByMissionIdOrderByCapturedAtDesc("mission")).thenReturn(List.of(asset("selected", MediaStatus.AVAILABLE)));
        when(reviewPublisher.publish(any(), any(), anyList(), anySet(), any(), any())).thenAnswer(call -> {
            var round = new ResultReviewRound(); round.setId("migrated-round"); return round;
        });

        var response = service.releasePreview("order", new ReleasePreviewRequest(List.of("selected"), "Republish legacy review"));

        assertThat(response.deliveryStatus()).isEqualTo(DeliveryStatus.CUSTOMER_REVIEW);
        assertThat(delivery.getCurrentReviewRoundId()).isEqualTo("migrated-round");
        verify(reviewPublisher).publish(any(), any(), anyList(), anySet(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {30, 90, 120})
    void originalsRequirePaidInvoiceAndStartTheSelectedRetentionPeriodOnlyOnce(int days) {
        order.setDataRetentionDays(days);
        assertThat(order.getResultExpiresAt()).isNull();
        assertThatThrownBy(() -> service.requireOriginalAccess("order"))
                .isInstanceOf(ApiException.class).extracting("errorCode").isEqualTo(ErrorCode.FINAL_PAYMENT_REQUIRED);
        invoice.setStatus(InvoiceStatus.PAID); invoice.setPaidAmount(invoice.getTotalAmount()); invoice.setRemainingAmount(BigDecimal.ZERO);
        delivery.setStatus(DeliveryStatus.READY_FOR_DELIVERY);
        delivery.setCustomerAcceptedAt(java.time.Instant.now());
        reviewRounds.findById("round").orElseThrow().setStatus(com.ondemandmonitoring.delivery.enums.ResultReviewRoundStatus.ACCEPTED);
        User manager = user("manager", RoleCode.MANAGER); when(users.getCurrentUser()).thenReturn(manager);
        when(media.findByMissionIdOrderByCapturedAtDesc("mission")).thenReturn(List.of(asset("original", MediaStatus.AVAILABLE)));
        when(missions.findAllById(List.of("mission"))).thenReturn(List.of(mission));
        when(roundEvidence.findCurrentPublishedMediaAssetIdsByOrderIds(List.of("order"))).thenReturn(java.util.Set.of("original"));
        service.releaseOriginals("order");
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(order.getResultExpiresAt()).isEqualTo(delivery.getOriginalsReleasedAt().plusSeconds(days * 24L * 60 * 60));

        var firstExpiry = order.getResultExpiresAt();
        service.releaseOriginals("order");
        assertThat(order.getResultExpiresAt()).isEqualTo(firstExpiry);
    }

    @Test void completedMissionGetsDeliveryReadModelWithoutMutationOnFirstRead() {
        when(deliveries.findByOrderId("order")).thenReturn(Optional.empty());

        var result = service.get("order");

        assertThat(result.deliveryStatus()).isEqualTo(DeliveryStatus.READY_FOR_MANAGER_REVIEW);
        verify(deliveries, never()).save(any());
        verifyNoInteractions(completion);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.IN_PROGRESS);
    }

    @Test void originalEligibilityFilteringDoesNotThrowForUndeliveredMissions() {
        when(missions.findByIdWithOrder("mission")).thenReturn(Optional.of(mission));

        assertThat(service.originalAccessibleMissionIds(List.of("mission"))).isEmpty();

        invoice.setStatus(InvoiceStatus.PAID); invoice.setRemainingAmount(BigDecimal.ZERO);
        delivery.setStatus(DeliveryStatus.DELIVERED);
        assertThat(service.originalAccessibleMissionIds(List.of("mission"))).isEmpty();
        delivery.setCustomerAcceptedAt(java.time.Instant.now());
        var accepted = new ResultReviewRound();
        accepted.setStatus(com.ondemandmonitoring.delivery.enums.ResultReviewRoundStatus.ACCEPTED);
        when(reviewRounds.findById(delivery.getCurrentReviewRoundId())).thenReturn(Optional.of(accepted));
        assertThat(service.originalAccessibleMissionIds(List.of("mission"))).containsExactly("mission");
        service.requireOriginalAccess("order");
        when(revisionCases.findByOrderIdAndActiveTrue("order")).thenReturn(Optional.of(new com.ondemandmonitoring.delivery.domain.ResultRevisionCase()));
        assertThat(service.originalAccessibleMissionIds(List.of("mission"))).isEmpty();
        assertThatThrownBy(() -> service.requireOriginalAccess("order")).isInstanceOf(ApiException.class);

    }

    @Test void oneApprovedResultCannotMakeMultiMissionDeliveryReady() {
        delivery.setStatus(DeliveryStatus.PROCESSING);
        var sibling = new Mission(); sibling.setId("sibling"); sibling.setOrder(order);
        sibling.setStatus(MissionStatus.COMPLETED);
        when(missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc("order")).thenReturn(List.of(mission, sibling));
        var first = new com.ondemandmonitoring.mission.domain.MissionResult(); first.setMission(mission);
        first.setApprovalStatus(MissionResultApprovalStatus.APPROVED);
        when(missionResults.findByMissionIds(List.of("mission", "sibling"))).thenReturn(List.of(first));
        service.markReadyForManagerReview("order");
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.PROCESSING);
        var second = new com.ondemandmonitoring.mission.domain.MissionResult(); second.setMission(sibling);
        second.setApprovalStatus(MissionResultApprovalStatus.APPROVED);
        when(missionResults.findByMissionIds(List.of("mission", "sibling"))).thenReturn(List.of(first, second));
        service.markReadyForManagerReview("order");
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.READY_FOR_MANAGER_REVIEW);
        verifyNoInteractions(completion);
    }

    @Test void cancelledSiblingIsExcludedAndDeliveryCannotRegressCompletedOrder() {
        var sibling = new Mission(); sibling.setId("cancelled"); sibling.setOrder(order);
        sibling.setStatus(MissionStatus.CANCELLED);
        when(missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc("order")).thenReturn(List.of(mission, sibling));
        delivery.setStatus(DeliveryStatus.PROCESSING); order.setOrderStatus(OrderStatus.COMPLETED);
        service.markReadyForManagerReview("order");
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.READY_FOR_MANAGER_REVIEW);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptingReworkHandsOverOriginalsAndStartsRetentionOnlyAtHandoff(boolean existingDeadline) {
        order.setOrderStatus(OrderStatus.COMPLETED);
        var originalExpiry = existingDeadline ? java.time.Instant.now().minusSeconds(86400) : null;
        order.setResultExpiresAt(originalExpiry);
        invoice.setStatus(InvoiceStatus.PAID); invoice.setRemainingAmount(BigDecimal.ZERO);
        invoice.setPaidAmount(invoice.getTotalAmount());
        var revisionCase = new com.ondemandmonitoring.delivery.domain.ResultRevisionCase();
        revisionCase.setId("case"); revisionCase.setOrder(order); revisionCase.setTicketId("ticket");
        when(revisionCases.findByOrderIdAndActiveTrueForUpdate("order")).thenReturn(Optional.of(revisionCase));
        var checklist = new OrderChecklistItem(); checklist.setId("checklist");
        var roundItem = new ResultReviewRoundItem(); roundItem.setChangedInRound(true); roundItem.setOrderChecklistItem(checklist);
        when(roundItems.findByRoundIdOrderByDisplayOrderAsc("round")).thenReturn(List.of(roundItem));
        var item = new com.ondemandmonitoring.delivery.domain.ResultRevisionCaseItem();
        item.setStatus(com.ondemandmonitoring.delivery.enums.ResultRevisionCaseItemStatus.WAITING_CUSTOMER);
        when(revisionCaseItems.findByResultRevisionCaseIdAndOrderChecklistItemIdInOrderByCreatedAtAsc(eq("case"), anyCollection()))
                .thenReturn(List.of(item));
        when(revisionCaseItems.findByResultRevisionCaseIdOrderByCreatedAtAsc("case")).thenReturn(List.of(item));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "supportTickets",
                mock(com.ondemandmonitoring.support.service.impl.SupportTicketService.class));
        assertThat(service.acceptResult("order", "round", 2L).deliveryStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(revisionCase.isActive()).isFalse();
        assertThat(item.getStatus()).isEqualTo(com.ondemandmonitoring.delivery.enums.ResultRevisionCaseItemStatus.RESOLVED);
        var expectedExpiry = originalExpiry == null
                ? delivery.getOriginalsReleasedAt().plus(java.time.Duration.ofDays(90)) : originalExpiry;
        assertThat(order.getResultExpiresAt()).isEqualTo(expectedExpiry);
        var closedAt = revisionCase.getClosedAt();
        service.acceptResult("order", "round", 2L);
        assertThat(order.getResultExpiresAt()).isEqualTo(expectedExpiry);
        assertThat(revisionCase.getClosedAt()).isEqualTo(closedAt);
    }

    private User user(String id, RoleCode code) {
        User user = new User(); user.setId(id); user.setRole(Role.builder().code(code).active(true).build()); return user;
    }
    private MediaAsset asset(String id, MediaStatus status) {
        MediaAsset asset = new MediaAsset(); asset.setId(id); asset.setMission(mission); asset.setMediaStatus(status);
        asset.setType("IMAGE"); asset.setOriginalFileName(id + ".jpg"); asset.setFileSize(10L);
        asset.setS3Bucket("private"); asset.setS3Key("missions/" + id); return asset;
    }
}
