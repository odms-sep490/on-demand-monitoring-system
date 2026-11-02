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
import com.ondemandmonitoring.delivery.service.impl.DeliveryWorkflowServiceImpl;
import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.domain.Quote;
import com.ondemandmonitoring.finance.enums.InvoiceStatus;
import com.ondemandmonitoring.finance.enums.QuoteStatus;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.enums.MissionResultApprovalStatus;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.repository.MissionResultRepository;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeliveryWorkflowServiceTest {
    OrderDeliveryRepository deliveries = mock(OrderDeliveryRepository.class);
    OrderRepository orders = mock(OrderRepository.class);
    MissionRepository missions = mock(MissionRepository.class);
    MissionResultRepository missionResults = mock(MissionResultRepository.class);
    InvoiceRepository invoices = mock(InvoiceRepository.class);
    MediaAssetRepository media = mock(MediaAssetRepository.class);
    IMediaObjectStorage storage = mock(IMediaObjectStorage.class);
    AuthenticatedUserResolver users = mock(AuthenticatedUserResolver.class);
    com.ondemandmonitoring.order.service.IOrderExecutionCompletionPolicy completion = mock(com.ondemandmonitoring.order.service.IOrderExecutionCompletionPolicy.class);
    DeliveryWorkflowServiceImpl service = new DeliveryWorkflowServiceImpl(
            deliveries, orders, missions, missionResults, invoices, media, storage, users, completion);
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
        when(storage.presignedUrlExpiresSeconds()).thenReturn(600L);
    }

    @Test void customerCanAcceptResultsWithoutASecondPaymentPrompt() {
        var result = service.acceptResult("order");
        assertThat(result.deliveryStatus()).isEqualTo(DeliveryStatus.FINAL_PAYMENT_PENDING);
        assertThat(delivery.getCustomerAcceptedAt()).isNotNull();
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.IN_PROGRESS);
    }

    @Test void customerAcceptanceAfterFullPaymentMovesDirectlyToDelivery() {
        invoice.setStatus(InvoiceStatus.PAID);
        invoice.setPaidAmount(invoice.getTotalAmount()); invoice.setRemainingAmount(BigDecimal.ZERO);
        var result = service.acceptResult("order");
        assertThat(result.deliveryStatus()).isEqualTo(DeliveryStatus.READY_FOR_DELIVERY);
        assertThat(delivery.getCustomerAcceptedAt()).isNotNull();
        assertThat(delivery.getFinalPaymentConfirmedAt()).isNull();
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.IN_PROGRESS);
    }

    @Test void revisionRequestLeavesResultReviewOpenWithoutChangingPaymentState() {
        service.requestRevision("order", new RevisionRequest("North side is unclear", List.of()));
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.REVISION_REQUESTED);
        assertThat(delivery.getRevisionCount()).isEqualTo(1);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
    }

    @Test void managerSelectsApprovedPreviewAssets() {
        delivery.setStatus(DeliveryStatus.READY_FOR_MANAGER_REVIEW);
        User manager = user("manager", RoleCode.MANAGER); when(users.getCurrentUser()).thenReturn(manager);
        MediaAsset selected = asset("selected", MediaStatus.AVAILABLE);
        MediaAsset excluded = asset("excluded", MediaStatus.AVAILABLE);
        when(media.findByMissionIdOrderByCapturedAtDesc("mission")).thenReturn(List.of(selected, excluded));
        when(storage.createPresignedGetUrl(anyString(), anyString())).thenReturn("https://signed-preview");
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
        when(storage.createPresignedGetUrl(anyString(), anyString())).thenReturn("https://example.test/preview");
        var response = service.releasePreview("order", new ReleasePreviewRequest(List.of("selected"), null));
        assertThat(response.deliveryStatus()).isEqualTo(DeliveryStatus.CUSTOMER_REVIEW);
        verify(deliveries).save(any(OrderDelivery.class));
        verify(completion).completeIfReady(any());
    }

    @Test void originalsRequirePaidInvoiceAndManagerRelease() {
        assertThatThrownBy(() -> service.requireOriginalAccess("order"))
                .isInstanceOf(ApiException.class).extracting("errorCode").isEqualTo(ErrorCode.FINAL_PAYMENT_REQUIRED);
        invoice.setStatus(InvoiceStatus.PAID); invoice.setPaidAmount(invoice.getTotalAmount()); invoice.setRemainingAmount(BigDecimal.ZERO);
        delivery.setStatus(DeliveryStatus.READY_FOR_DELIVERY);
        User manager = user("manager", RoleCode.MANAGER); when(users.getCurrentUser()).thenReturn(manager);
        when(media.findByMissionIdOrderByCapturedAtDesc("mission")).thenReturn(List.of(asset("original", MediaStatus.AVAILABLE)));
        when(storage.createPresignedGetUrl(anyString(), anyString())).thenReturn("https://signed-original");
        service.releaseOriginals("order");
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(order.getResultExpiresAt()).isEqualTo(delivery.getOriginalsReleasedAt().plusSeconds(90L * 24 * 60 * 60));

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

        invoice.setStatus(InvoiceStatus.PAID);
        delivery.setStatus(DeliveryStatus.DELIVERED);
        assertThat(service.originalAccessibleMissionIds(List.of("mission"))).containsExactly("mission");
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

    private User user(String id, RoleCode code) {
        User user = new User(); user.setId(id); user.setRole(Role.builder().code(code).active(true).build()); return user;
    }
    private MediaAsset asset(String id, MediaStatus status) {
        MediaAsset asset = new MediaAsset(); asset.setId(id); asset.setMission(mission); asset.setMediaStatus(status);
        asset.setType("IMAGE"); asset.setOriginalFileName(id + ".jpg"); asset.setFileSize(10L);
        asset.setS3Bucket("private"); asset.setS3Key("missions/" + id); return asset;
    }
}
