package com.ondemandmonitoring.delivery.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.delivery.domain.OrderDelivery;
import com.ondemandmonitoring.delivery.dto.request.ReleasePreviewRequest;
import com.ondemandmonitoring.delivery.dto.request.RevisionRequest;
import com.ondemandmonitoring.delivery.dto.response.DeliveryAssetResponse;
import com.ondemandmonitoring.delivery.dto.response.DeliveryResponse;
import com.ondemandmonitoring.delivery.enums.DeliveryStatus;
import com.ondemandmonitoring.delivery.repository.OrderDeliveryRepository;
import com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService;
import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.enums.InvoiceStatus;
import com.ondemandmonitoring.finance.enums.QuoteStatus;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.media.service.IResultRetentionAccessService;
import com.ondemandmonitoring.media.service.impl.ResultRetentionAccessServiceImpl;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.enums.MissionResultApprovalStatus;
import com.ondemandmonitoring.mission.repository.MissionResultRepository;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional implementation of the order delivery state machine.
 *
 * <p>The service is the single authority for advancing delivery state. It also
 * prevents original media from being exposed before both final payment and the
 * manager's explicit release have completed.</p>
 */
@Service
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
public class DeliveryWorkflowServiceImpl implements IDeliveryWorkflowService {
    private final OrderDeliveryRepository deliveries;
    private final OrderRepository orders;
    private final MissionRepository missions;
    private final MissionResultRepository missionResults;
    private final InvoiceRepository invoices;
    private final MediaAssetRepository media;
    private final IMediaObjectStorage storage;
    private final IResultRetentionAccessService retentionAccess;
    private final AuthenticatedUserResolver currentUser;
    private final com.ondemandmonitoring.order.service.IOrderExecutionCompletionPolicy completionPolicy;

    /** Compatibility constructor for focused unit tests and non-Spring callers. */
    public DeliveryWorkflowServiceImpl(OrderDeliveryRepository deliveries, OrderRepository orders,
            MissionRepository missions, MissionResultRepository missionResults, InvoiceRepository invoices,
            MediaAssetRepository media, IMediaObjectStorage storage, AuthenticatedUserResolver currentUser,
            com.ondemandmonitoring.order.service.IOrderExecutionCompletionPolicy completionPolicy) {
        this(deliveries, orders, missions, missionResults, invoices, media, storage,
                new ResultRetentionAccessServiceImpl(java.time.Clock.systemUTC()), currentUser, completionPolicy);
    }

    @Override
    @Transactional
    public void markProcessing(String orderId) {
        Order order = orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        deliveries.findByOrderIdForUpdate(orderId).orElseGet(() -> create(order));
        keepOrderInProgress(order);
    }

    @Override
    @Transactional
    public void markReadyForManagerReview(String orderId) {
        Order order = orders.findByIdForUpdate(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        OrderDelivery delivery = deliveries.findByOrderIdForUpdate(orderId).orElseGet(() -> create(order));
        if (delivery.getStatus() == DeliveryStatus.PROCESSING && isResultApproved(orderId)) {
            delivery.setStatus(DeliveryStatus.READY_FOR_MANAGER_REVIEW);
        }
        keepOrderInProgress(order);
    }

    @Override
    @Transactional
    public DeliveryResponse releasePreview(String orderId, ReleasePreviewRequest request) {
        List<Mission> effective = completedMissions(orderId);
        if (!isResultApproved(orderId) || !completionPolicy.completeIfReady(completionPolicy.lockAggregate(orderId))) {
            throw new ApiException(ErrorCode.DELIVERY_NOT_READY,
                    "Approve the mission result before releasing a customer preview");
        }
        // Legacy completed Orders may have no delivery row. Initialize it only
        // inside this validated write command, never from the read endpoint.
        OrderDelivery delivery = deliveries.findByOrderIdForUpdate(orderId).orElseGet(() -> {
            OrderDelivery initialized = create(orders.findByIdForUpdate(orderId).orElseThrow());
            initialized.setStatus(DeliveryStatus.READY_FOR_MANAGER_REVIEW);
            return initialized;
        });
        if (delivery.getStatus() != DeliveryStatus.READY_FOR_MANAGER_REVIEW
                && delivery.getStatus() != DeliveryStatus.REVISION_REQUESTED) {
            throw new ApiException(ErrorCode.INVALID_DELIVERY_STATE);
        }
        List<MediaAsset> assets = assets(effective);
        Set<String> selected = new HashSet<>(request.mediaAssetIds());
        if (selected.isEmpty() || assets.stream().filter(a -> selected.contains(a.getId()))
                .anyMatch(a -> a.getMediaStatus() != MediaStatus.AVAILABLE)
                || assets.stream().noneMatch(a -> selected.contains(a.getId()))) {
            throw new ApiException(ErrorCode.DELIVERY_NOT_READY, "Select at least one approved mission asset");
        }
        if (selected.stream().anyMatch(id -> assets.stream().noneMatch(a -> a.getId().equals(id)))) {
            throw new ApiException(ErrorCode.MEDIA_ACCESS_DENIED);
        }
        assets.forEach(asset -> asset.setPreviewSelected(selected.contains(asset.getId())));
        media.saveAll(assets);
        Instant now = Instant.now();
        delivery.setStatus(DeliveryStatus.CUSTOMER_REVIEW);
        delivery.setDeliveryNotes(trim(request.notes()));
        delivery.setPreviewReleasedAt(now);
        delivery.setPreviewReleasedBy(currentUser.getCurrentUser());
        delivery.setRevisionReason(null);
        delivery.setRevisionRequestedAt(null);
        return response(delivery, effective, assets.stream().filter(MediaAsset::isPreviewSelected).toList(), false);
    }

    @Override
    @Transactional
    public DeliveryResponse acceptResult(String orderId) {
        assertOwner(orderId);
        List<Mission> effective = completedMissions(orderId);
        OrderDelivery delivery = locked(orderId);
        if (delivery.getStatus() == DeliveryStatus.FINAL_PAYMENT_PENDING && delivery.getCustomerAcceptedAt() != null) {
            return response(delivery, effective, List.of(), false);
        }
        if (delivery.getStatus() != DeliveryStatus.CUSTOMER_REVIEW) {
            throw new ApiException(delivery.getCustomerAcceptedAt() != null
                    ? ErrorCode.RESULT_ALREADY_ACCEPTED : ErrorCode.RESULT_NOT_READY_FOR_REVIEW);
        }
        Invoice invoice = invoice(orderId);
        if (invoice.getStatus() == InvoiceStatus.PAID) throw new ApiException(ErrorCode.INVOICE_ALREADY_PAID);
        delivery.setCustomerAcceptedAt(Instant.now());
        delivery.setStatus(DeliveryStatus.FINAL_PAYMENT_PENDING);
        return response(delivery, effective, List.of(), false);
    }

    @Override
    @Transactional
    public DeliveryResponse requestRevision(String orderId, RevisionRequest request) {
        assertOwner(orderId);
        List<Mission> effective = completedMissions(orderId);
        OrderDelivery delivery = locked(orderId);
        if (delivery.getStatus() != DeliveryStatus.CUSTOMER_REVIEW) throw new ApiException(ErrorCode.REVISION_NOT_ALLOWED);
        if (request.mediaAssetIds() != null && !request.mediaAssetIds().isEmpty()) {
            Set<String> ids = new HashSet<>(request.mediaAssetIds());
            boolean invalid = assets(effective).stream()
                    .filter(a -> ids.contains(a.getId())).count() != ids.size();
            if (invalid) throw new ApiException(ErrorCode.MEDIA_ACCESS_DENIED);
        }
        delivery.setStatus(DeliveryStatus.REVISION_REQUESTED);
        delivery.setRevisionReason(request.reason().trim());
        delivery.setRevisionRequestedAt(Instant.now());
        delivery.setRevisionCount(delivery.getRevisionCount() + 1);
        return response(delivery, effective, List.of(), false);
    }

    @Override
    @Transactional
    public void confirmFinalPayment(String orderId) {
        orders.findByIdForUpdate(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        OrderDelivery delivery = locked(orderId);
        if (delivery.getStatus() == DeliveryStatus.READY_FOR_DELIVERY
                || delivery.getStatus() == DeliveryStatus.DELIVERED) return;
        if (delivery.getStatus() != DeliveryStatus.FINAL_PAYMENT_PENDING) {
            throw new ApiException(ErrorCode.INVALID_DELIVERY_STATE);
        }
        delivery.setStatus(DeliveryStatus.PAYMENT_CONFIRMED);
        delivery.setFinalPaymentConfirmedAt(Instant.now());
        delivery.setStatus(DeliveryStatus.READY_FOR_DELIVERY);
    }

    @Override
    @Transactional(readOnly = true)
    public void requireFinalPaymentEligible(String orderId) {
        List<Mission> effective = effectiveMissions(orderId);
        if (effective.isEmpty() || effective.stream().anyMatch(m -> m.getStatus() != MissionStatus.COMPLETED))
            throw new ApiException(ErrorCode.FINAL_PAYMENT_NOT_ALLOWED);
        OrderDelivery delivery = deliveries.findByOrderId(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.FINAL_PAYMENT_NOT_ALLOWED));
        Invoice invoice = invoice(orderId);
        boolean valid = !effective.isEmpty()
                && delivery.getStatus() == DeliveryStatus.FINAL_PAYMENT_PENDING
                && delivery.getCustomerAcceptedAt() != null
                && invoice.getQuote().getStatus() == QuoteStatus.ACCEPTED_BY_CUSTOMER
                && invoice.getStatus() != InvoiceStatus.PAID && invoice.getRemainingAmount().signum() > 0;
        if (!valid) throw new ApiException(ErrorCode.FINAL_PAYMENT_NOT_ALLOWED);
    }

    @Override
    @Transactional
    public DeliveryResponse releaseOriginals(String orderId) {
        List<Mission> effective = completedMissions(orderId);
        Invoice invoice = invoice(orderId);
        if (invoice.getStatus() != InvoiceStatus.PAID) throw new ApiException(ErrorCode.INVOICE_NOT_PAID);
        OrderDelivery delivery = locked(orderId);
        if (delivery.getStatus() == DeliveryStatus.DELIVERED) {
            return response(delivery, effective, assets(effective).stream()
                    .filter(a -> a.getMediaStatus() == MediaStatus.AVAILABLE).toList(), true);
        }
        if (delivery.getStatus() != DeliveryStatus.READY_FOR_DELIVERY) throw new ApiException(ErrorCode.DELIVERY_NOT_READY);
        List<MediaAsset> assets = assets(effective).stream()
                .filter(a -> a.getMediaStatus() == MediaStatus.AVAILABLE).toList();
        if (assets.isEmpty()) throw new ApiException(ErrorCode.DELIVERY_NOT_READY, "No approved originals exist");
        Instant handedOverAt = Instant.now();
        delivery.setStatus(DeliveryStatus.DELIVERED);
        delivery.setOriginalsReleasedAt(handedOverAt);
        delivery.setOriginalsReleasedBy(currentUser.getCurrentUser());
        Order order = delivery.getOrder();
        if (order.getResultExpiresAt() == null) {
            order.setResultExpiresAt(handedOverAt.plus(order.getDataRetentionDays(), java.time.temporal.ChronoUnit.DAYS));
        }
        if (!completionPolicy.completeIfReady(completionPolicy.lockAggregate(orderId)))
            throw new ApiException(ErrorCode.DELIVERY_NOT_READY);
        return response(delivery, effective, assets, true);
    }

    @Override @Transactional(readOnly = true)
    public DeliveryResponse get(String orderId) {
        assertOwnerOrManager(orderId);
        var effective = effectiveMissions(orderId);
        OrderDelivery delivery = deliveries.findByOrderId(orderId).orElseGet(() -> {
            OrderDelivery view = new OrderDelivery();
            view.setOrder(orders.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND)));
            view.setStatus(isResultApproved(orderId) ? DeliveryStatus.READY_FOR_MANAGER_REVIEW : DeliveryStatus.PROCESSING);
            return view; // Unsaved read model: GET must never create/reconcile business state.
        });
        List<MediaAsset> visible = isManager() ? assets(effective).stream()
                .filter(a -> a.getMediaStatus() == MediaStatus.AVAILABLE).toList() : List.of();
        return response(delivery, effective, visible, false);
    }

    @Override @Transactional(readOnly = true)
    public DeliveryResponse previews(String orderId) {
        assertOwnerOrManager(orderId);
        OrderDelivery delivery = deliveries.findByOrderId(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESULT_NOT_READY_FOR_REVIEW));
        if (delivery.getPreviewReleasedAt() == null || delivery.getStatus().ordinal() < DeliveryStatus.CUSTOMER_REVIEW.ordinal()) {
            throw new ApiException(ErrorCode.RESULT_NOT_READY_FOR_REVIEW);
        }
        List<Mission> effective = effectiveMissions(orderId);
        List<MediaAsset> assets = assets(effective).stream()
                .filter(MediaAsset::isPreviewSelected).filter(a -> a.getMediaStatus() == MediaStatus.AVAILABLE).toList();
        return response(delivery, effective, assets, false);
    }

    @Override @Transactional(readOnly = true)
    public DeliveryResponse originals(String orderId) {
        assertOwnerOrManager(orderId);
        requireOriginalAccess(orderId);
        OrderDelivery delivery = deliveries.findByOrderId(orderId).orElseThrow();
        List<Mission> effective = effectiveMissions(orderId);
        List<MediaAsset> assets = assets(effective).stream()
                .filter(a -> a.getMediaStatus() == MediaStatus.AVAILABLE).toList();
        return response(delivery, effective, assets, true);
    }

    @Override @Transactional(readOnly = true)
    public void requireOriginalAccess(String orderId) {
        if (isManager()) return;
        assertOwner(orderId);
        Invoice invoice = invoice(orderId);
        if (invoice.getStatus() != InvoiceStatus.PAID) throw new ApiException(ErrorCode.FINAL_PAYMENT_REQUIRED);
        OrderDelivery delivery = deliveries.findByOrderId(orderId).orElseThrow(() -> new ApiException(ErrorCode.DELIVERABLE_NOT_RELEASED));
        if (delivery.getStatus() != DeliveryStatus.DELIVERED) throw new ApiException(ErrorCode.DELIVERABLE_NOT_RELEASED);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> originalAccessibleMissionIds(List<String> missionIds) {
        if (missionIds == null || missionIds.isEmpty()) return List.of();
        String customerId = currentUser.getCurrentUserId();
        return missionIds.stream().filter(missionId -> missions.findByIdWithOrder(missionId)
                .map(Mission::getOrder)
                .filter(order -> order.getCustomer().getId().equals(customerId))
                .filter(order -> invoices.findByOrderId(order.getId())
                        .filter(invoice -> invoice.getStatus() == InvoiceStatus.PAID).isPresent())
                .filter(order -> deliveries.findByOrderId(order.getId())
                        .filter(delivery -> delivery.getStatus() == DeliveryStatus.DELIVERED).isPresent())
                .isPresent()).toList();
    }

    private OrderDelivery locked(String orderId) {
        return deliveries.findByOrderIdForUpdate(orderId).orElseThrow(() -> new ApiException(ErrorCode.DELIVERY_NOT_READY));
    }
    private OrderDelivery create(Order order) {
        OrderDelivery d = new OrderDelivery(); d.setOrder(order); d.setStatus(DeliveryStatus.PROCESSING); return deliveries.save(d);
    }
    private boolean isResultApproved(String orderId) {
        var effective = effectiveMissions(orderId);
        if (effective.isEmpty()) return false;
        var results = missionResults.findByMissionIds(effective.stream().map(Mission::getId).toList()).stream()
                .collect(java.util.stream.Collectors.toMap(r -> r.getMission().getId(), r -> r));
        return effective.stream().allMatch(m -> m.getStatus() == MissionStatus.COMPLETED
                && results.containsKey(m.getId())
                && results.get(m.getId()).getApprovalStatus() == MissionResultApprovalStatus.APPROVED
                && results.get(m.getId()).getStatus() != com.ondemandmonitoring.mission.enums.MissionResultStatus.FAILED);
    }
    private void keepOrderInProgress(Order order) {
        if (order.getOrderStatus() == OrderStatus.APPROVED) order.setOrderStatus(OrderStatus.IN_PROGRESS);
    }
    private List<Mission> effectiveMissions(String orderId) {
        return missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc(orderId).stream()
                .filter(com.ondemandmonitoring.mission.service.impl.ExecutionPlanCoverageService::effective).toList();
    }
    private List<Mission> completedMissions(String orderId) {
        // All write commands acquire Order before Mission and delivery locks.
        var aggregate = completionPolicy.lockAggregate(orderId);
        var effective = aggregate.missions().stream()
                .filter(com.ondemandmonitoring.mission.service.impl.ExecutionPlanCoverageService::effective).toList();
        if (effective.isEmpty() || effective.stream().anyMatch(m -> m.getStatus() != MissionStatus.COMPLETED))
            throw new ApiException(ErrorCode.MISSION_NOT_COMPLETED);
        return effective;
    }
    private List<MediaAsset> assets(List<Mission> effective) {
        return effective.stream().flatMap(m -> media.findByMissionIdOrderByCapturedAtDesc(m.getId()).stream()).toList();
    }
    private MissionStatus executionStatus(List<Mission> effective) {
        if (effective.isEmpty()) return null;
        var states = effective.stream().map(Mission::getStatus).distinct().toList();
        return states.size() == 1 ? states.getFirst() : MissionStatus.IN_PROGRESS;
    }
    private Invoice invoice(String orderId) {
        return invoices.findByOrderId(orderId).orElseThrow(() -> new ApiException(ErrorCode.INVOICE_NOT_FOUND));
    }
    private void assertOwner(String orderId) {
        Order order = orders.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!order.getCustomer().getId().equals(currentUser.getCurrentUserId())) throw new ApiException(ErrorCode.ACCESS_DENIED);
    }
    private void assertOwnerOrManager(String orderId) { if (!isManager()) assertOwner(orderId); }
    private boolean isManager() {
        User user = currentUser.getCurrentUser();
        return user.getRole() != null && (user.getRole().getCode() == RoleCode.MANAGER || user.getRole().getCode() == RoleCode.ADMIN);
    }
    private DeliveryResponse response(OrderDelivery d, List<Mission> effective, List<MediaAsset> assets, boolean downloadable) {
        Invoice invoice = invoice(d.getOrder().getId());
        Instant expires = Instant.now().plusSeconds(storage.presignedUrlExpiresSeconds());
        List<DeliveryAssetResponse> mapped = assets.stream().map(a -> new DeliveryAssetResponse(a.getId(), a.getType(),
                a.getOriginalFileName(), a.getFileSize(), retainedUrl(a),
                expires, downloadable)).toList();
        return new DeliveryResponse(d.getOrder().getId(), d.getStatus(), executionStatus(effective), d.getOrder().getOrderStatus(),
                invoice.getStatus(), invoice.getTotalAmount(), invoice.getPaidAmount(), invoice.getRemainingAmount(),
                d.getDeliveryNotes(), d.getPreviewReleasedAt(), d.getCustomerAcceptedAt(), d.getRevisionRequestedAt(),
                d.getRevisionReason(), d.getRevisionCount(), d.getFinalPaymentConfirmedAt(), d.getOriginalsReleasedAt(), mapped);
    }
    private String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String retainedUrl(MediaAsset asset) {
        retentionAccess.requireAccessible(asset);
        return storage.createPresignedGetUrl(asset.getS3Bucket(), asset.getS3Key());
    }
}
