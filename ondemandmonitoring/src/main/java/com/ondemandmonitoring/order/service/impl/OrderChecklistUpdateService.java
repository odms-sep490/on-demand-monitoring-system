package com.ondemandmonitoring.order.service.impl;

import com.ondemandmonitoring.checklist.domain.ChecklistDefinition;
import com.ondemandmonitoring.checklist.repository.ChecklistDefinitionRepository;
import com.ondemandmonitoring.checklist.repository.ServiceChecklistRepository;
import com.ondemandmonitoring.checklist.util.ChecklistContentNormalizer;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.finance.domain.Quote;
import com.ondemandmonitoring.finance.enums.QuoteStatus;
import com.ondemandmonitoring.finance.repository.QuoteRepository;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.domain.OrderChecklistRevision;
import com.ondemandmonitoring.order.dto.request.OrderChecklistUpdateItemRequest;
import com.ondemandmonitoring.order.dto.request.OrderChecklistUpdateRequest;
import com.ondemandmonitoring.order.dto.response.OrderCreateResponse;
import com.ondemandmonitoring.order.enums.ChecklistPricingStatus;
import com.ondemandmonitoring.order.enums.ChecklistReviewStatus;
import com.ondemandmonitoring.order.enums.OrderChecklistSourceType;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.enums.RequiredEvidenceType;
import com.ondemandmonitoring.order.repository.OrderChecklistItemRepository;
import com.ondemandmonitoring.order.repository.OrderChecklistRevisionRepository;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.order.service.IOrderChecklistUpdateService;
import com.ondemandmonitoring.order.service.IOrderService;
import com.ondemandmonitoring.pricing.domain.PricingPolicy;
import com.ondemandmonitoring.pricing.enums.ChecklistDifficulty;
import com.ondemandmonitoring.pricing.enums.MediaPricingComponent;
import com.ondemandmonitoring.pricing.repository.PricingPolicyRepository;
import com.ondemandmonitoring.pricing.service.ChecklistPriceCalculator;
import com.ondemandmonitoring.pricing.service.ServicePriceResolver;
import com.ondemandmonitoring.service.domain.Service;
import com.ondemandmonitoring.service.repository.ServiceRepository;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

@org.springframework.stereotype.Service
@RequiredArgsConstructor
public class OrderChecklistUpdateService implements IOrderChecklistUpdateService {
    private static final List<QuoteStatus> UNACCEPTED_ACTIVE_QUOTE_STATUSES = List.of(
            QuoteStatus.DRAFT, QuoteStatus.PENDING_REVIEW, QuoteStatus.APPROVED);

    private final OrderRepository orderRepository;
    private final ServiceRepository services;
    private final ChecklistDefinitionRepository definitions;
    private final ServiceChecklistRepository assignments;
    private final OrderChecklistItemRepository snapshots;
    private final OrderChecklistRevisionRepository revisionRepository;
    private final QuoteRepository quoteRepository;
    private final PricingPolicyRepository pricingPolicies;
    private final ChecklistPriceCalculator priceCalculator;
    private final ServicePriceResolver servicePrices;
    private final OrderChecklistSnapshotService snapshotService;
    private final OrderChecklistEditPolicy editPolicy;
    private final AuthenticatedUserResolver currentUser;
    private final IOrderService orderService;

    @Override
    @Transactional
    @PreAuthorize("hasRole('CUSTOMER')")
    public OrderCreateResponse updateChecklist(String orderId, OrderChecklistUpdateRequest request) {
        if (request == null || request.getExpectedRevision() == null || request.getExpectedRevision() < 0) {
            throw invalid("expectedRevision is required");
        }
        List<OrderChecklistUpdateItemRequest> requested = request.getItems() == null ? List.of() : request.getItems();
        if (requested.size() > 100) {
            throw invalid("At most 100 checklist items are allowed");
        }

        Order order = orderRepository.findByIdForUpdate(orderId)
                .or(() -> orderRepository.findById(orderId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found: " + orderId));
        User actor = currentUser.getCurrentUser();
        if (actor == null) {
            throw new ApiException(ErrorCode.ACCESS_DENIED);
        }
        if (order.getCustomer() == null || !Objects.equals(order.getCustomer().getId(), actor.getId())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED);
        }

        long currentRevision = order.getChecklistRevision();
        List<OrderChecklistItem> currentItems = snapshots
                .findAllByOrderIdAndChecklistRevisionOrderByDisplayOrderAscIdAsc(orderId, currentRevision);

        Service service = services.findByIdForUpdate(order.getService().getId())
                .or(() -> services.findById(order.getService().getId()))
                .orElseThrow(() -> new ApiException(ErrorCode.SERVICE_NOT_FOUND));
        if (!Boolean.TRUE.equals(service.getIsActive())) {
            throw new ApiException(ErrorCode.SERVICE_INACTIVE);
        }

        List<String> templateIds = assignments.findTemplateChecklistIds(service.getId());
        Set<String> membership = new HashSet<>(templateIds);
        Map<String, ServiceChecklistRepository.TemplateOverride> overrides = new HashMap<>();
        for (var override : assignments.findTemplateOverrides(service.getId())) {
            overrides.put(override.getChecklistId(), override);
        }

        PricingPolicy policy = resolveOrderPolicy(order);

        if (!Objects.equals(request.getExpectedRevision(), currentRevision)) {
            if (request.getExpectedRevision() == currentRevision - 1 && currentRevision > 1) {
                List<OrderChecklistItem> previousItems = snapshots
                        .findAllByOrderIdAndChecklistRevisionOrderByDisplayOrderAscIdAsc(
                                orderId, request.getExpectedRevision());
                if (isIdempotentRetry(order, service, previousItems, currentItems, requested, membership, overrides,
                        policy)) {
                    return orderService.getOrderById(orderId);
                }
            }
            throw new ApiException(ErrorCode.ORDER_CHECKLIST_REVISION_CONFLICT);
        }

        editPolicy.evaluate(order, actor.getId()).assertEditable();

        long nextRevision = currentRevision + 1;
        List<OrderChecklistItem> candidateItems = buildCandidateItems(
                order, service, currentRevision, nextRevision, currentItems, requested, membership, overrides, policy);

        if (isNoOp(currentItems, candidateItems, requested)) {
            return orderService.getOrderById(orderId);
        }

        if (policy != null && order.getPricingPolicyId() == null) {
            order.setPricingPolicyId(policy.getId());
            order.setPricingPolicyVersion(policy.getPolicyVersion());
        }
        order.setServiceBasePriceSnapshot(null);

        List<OrderChecklistItem> saved = snapshots.saveAllAndFlush(candidateItems);
        order.setChecklistRevision(nextRevision);
        order.setChecklistSnapshotAt(Instant.now());
        snapshotService.applySystemPrices(order, saved);

        if (order.getOrderStatus() == OrderStatus.APPROVED) {
            order.setOrderStatus(OrderStatus.PENDING);
            order.setReviewBy(null);
            order.setReviewAt(null);
        }

        List<Quote> activeQuotes = quoteRepository.findHistory(orderId).stream()
                .filter(q -> UNACCEPTED_ACTIVE_QUOTE_STATUSES.contains(q.getStatus()))
                .toList();
        if (!activeQuotes.isEmpty()) {
            activeQuotes.forEach(q -> q.setStatus(QuoteStatus.SUPERSEDED));
            quoteRepository.saveAll(activeQuotes);
        }

        if (revisionRepository != null) {
            OrderChecklistRevision rev = new OrderChecklistRevision();
            rev.setOrder(order);
            rev.setRevision(nextRevision);
            rev.setActor(actor);
            rev.setChangeSummary(buildChangeSummary(currentItems, saved, nextRevision));
            rev.setPayloadFingerprint(OrderChecklistSnapshotService.fingerprint(saved));
            revisionRepository.save(rev);
        }

        orderRepository.saveAndFlush(order);
        return orderService.getOrderById(orderId);
    }

    private boolean isIdempotentRetry(
            Order order,
            Service service,
            List<OrderChecklistItem> baseItems,
            List<OrderChecklistItem> currentItems,
            List<OrderChecklistUpdateItemRequest> requested,
            Set<String> membership,
            Map<String, ServiceChecklistRepository.TemplateOverride> overrides,
            PricingPolicy policy) {
        try {
            List<OrderChecklistItem> retryCandidates = buildCandidateItems(
                    order, service, order.getChecklistRevision() - 1, order.getChecklistRevision(),
                    baseItems, requested, membership, overrides, policy);
            String candidateFp = OrderChecklistSnapshotService.fingerprint(retryCandidates);
            String currentFp = OrderChecklistSnapshotService.fingerprint(currentItems);
            return Objects.equals(candidateFp, currentFp);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private List<OrderChecklistItem> buildCandidateItems(
            Order order,
            Service service,
            long baseRevision,
            long targetRevision,
            List<OrderChecklistItem> baseItems,
            List<OrderChecklistUpdateItemRequest> requested,
            Set<String> membership,
            Map<String, ServiceChecklistRepository.TemplateOverride> overrides,
            PricingPolicy policy) {
        Map<String, OrderChecklistItem> baseById = baseItems.stream()
                .collect(Collectors.toMap(OrderChecklistItem::getId, Function.identity()));

        Set<String> seenExistingIds = new HashSet<>();
        Set<String> finalSourceChecklistIds = new HashSet<>();
        Set<String> newCatalogIdsToLock = new TreeSet<>();
        Map<String, Long> expectedVersions = new HashMap<>();

        for (OrderChecklistUpdateItemRequest item : requested) {
            if (item == null) {
                throw invalid("Checklist item cannot be null");
            }
            if (item.getSourceChecklistId() != null && item.getSourceChecklistId().isBlank()) {
                throw new ApiException(ErrorCode.ORDER_CHECKLIST_TEMPLATE_CHANGED);
            }
            if (item.getExistingItemId() != null && item.getExistingItemId().isBlank()) {
                throw invalid("existingItemId cannot be blank");
            }

            String existingId = item.getExistingItemId();
            String sourceId = item.getSourceChecklistId();

            if (existingId != null && sourceId != null) {
                throw invalid("Specify either existingItemId or sourceChecklistId, not both");
            }

            if (existingId != null) {
                if (item.getExpectedChecklistVersion() != null) {
                    throw invalid("Existing snapshot item cannot declare expectedChecklistVersion");
                }
                if (!seenExistingIds.add(existingId)) {
                    throw invalid("Duplicate existing checklist item");
                }
                OrderChecklistItem existing = baseById.get(existingId);
                if (existing == null) {
                    var anyRevItem = snapshots.findById(existingId);
                    if (anyRevItem.isPresent()
                            && anyRevItem.get().getOrder() != null
                            && Objects.equals(anyRevItem.get().getOrder().getId(), order.getId())
                            && anyRevItem.get().getChecklistRevision() != baseRevision) {
                        throw new ApiException(ErrorCode.ORDER_CHECKLIST_REVISION_CONFLICT);
                    }
                    throw invalid("Checklist item does not belong to current order snapshot");
                }
                if (existing.getSourceChecklist() != null) {
                    if (!finalSourceChecklistIds.add(existing.getSourceChecklist().getId())) {
                        throw invalid("Duplicate source checklist");
                    }
                }
            } else if (sourceId != null) {
                if (!finalSourceChecklistIds.add(sourceId)) {
                    throw invalid("Duplicate source checklist");
                }
                if (item.getExpectedChecklistVersion() == null || item.getExpectedChecklistVersion() < 0) {
                    throw invalid("expectedChecklistVersion is required for a catalog item");
                }
                newCatalogIdsToLock.add(sourceId);
                expectedVersions.put(sourceId, item.getExpectedChecklistVersion());
            } else {
                if (item.getExpectedChecklistVersion() != null) {
                    throw invalid("Custom item cannot declare a catalog version");
                }
            }
        }

        for (var override : overrides.values()) {
            if (override.isRequired()
                    && Boolean.TRUE.equals(override.getChecklistActive())
                    && !finalSourceChecklistIds.contains(override.getChecklistId())) {
                throw new ApiException(ErrorCode.ORDER_CHECKLIST_REQUIRED_MISSING);
            }
        }

        Map<String, ChecklistDefinition> lockedCatalog = new HashMap<>();
        for (String id : newCatalogIdsToLock) {
            ChecklistDefinition def = definitions.findByIdForUpdate(id)
                    .orElseThrow(() -> new ApiException(ErrorCode.ORDER_CHECKLIST_TEMPLATE_CHANGED));
            if (!Boolean.TRUE.equals(def.getIsActive())
                    || !Objects.equals(def.getVersion(), expectedVersions.get(id))) {
                throw new ApiException(ErrorCode.ORDER_CHECKLIST_TEMPLATE_CHANGED);
            }
            lockedCatalog.put(id, def);
        }

        List<OrderChecklistItem> entities = new ArrayList<>();
        Set<String> contents = new HashSet<>();
        BigDecimal pricedTotal = BigDecimal.ZERO;

        for (OrderChecklistUpdateItemRequest item : requested) {
            boolean explicitEvidence = item.getMinimumEvidenceCount() != null || item.getRequiredEvidenceType() != null;
            boolean explicitMedia = item.getRequestedImageCount() != null || item.getRequestedVideoCount() != null;
            if (explicitEvidence && explicitMedia) {
                throw invalid("Use requested media counts or an explicit evidence requirement, not both");
            }
            if ((item.getRequestedImageCount() != null
                    && (item.getRequestedImageCount() < 0 || item.getRequestedImageCount() > 500))
                    || (item.getRequestedVideoCount() != null
                            && (item.getRequestedVideoCount() < 0 || item.getRequestedVideoCount() > 500))) {
                throw invalid("Requested media counts must be between 0 and 500");
            }

            OrderChecklistItem entity = new OrderChecklistItem();
            entity.setOrder(order);
            entity.setChecklistRevision(targetRevision);

            if (item.getExistingItemId() != null) {
                OrderChecklistItem existing = baseById.get(item.getExistingItemId());
                populateFromExistingSnapshot(entity, existing, item, explicitMedia, explicitEvidence);
            } else {
                ChecklistDefinition source = item.getSourceChecklistId() == null
                        ? null
                        : lockedCatalog.get(item.getSourceChecklistId());
                var override = source == null ? null : overrides.get(source.getId());
                populateNewSnapshot(entity, source, override, item, membership, policy, explicitMedia,
                        explicitEvidence);
            }

            if (!contents.add(entity.getContent().toLowerCase(Locale.ROOT))) {
                throw invalid("Duplicate checklist content");
            }
            entity.setDisplayOrder(entities.size());

            boolean custom = entity.getSourceType() == OrderChecklistSourceType.CUSTOMER_CUSTOM;
            if (policy != null) {
                pricedTotal = pricedTotal.add(priceCalculator.price(policy, new ChecklistPriceCalculator.Input(
                        entity.getSourceChecklist() == null ? null : entity.getSourceChecklist().getId(),
                        entity.getContent(),
                        entity.getDifficultySnapshot(),
                        custom,
                        entity.getBasePriceSnapshot(),
                        custom ? null : entity.getIncludedImageCountSnapshot(),
                        custom ? null : entity.getIncludedVideoCountSnapshot(),
                        entity.getRequestedImageCount(),
                        entity.getRequestedVideoCount(),
                        entity.getPricingProfileSnapshot(),
                        entity.getStandardVideoDurationSnapshot(),
                        false)).subtotal());
            }
            entities.add(entity);
        }

        if (entities.size() > 100) {
            throw invalid("At most 100 checklist items are allowed");
        }
        if (policy != null && pricedTotal.signum() == 0
                && ServicePriceResolver.UNPRICED.equals(servicePrices.resolveOrLegacy(service).source())
                && entities.stream().noneMatch(e -> e.getSourceType() == OrderChecklistSourceType.CUSTOMER_CUSTOM)) {
            throw new ApiException(ErrorCode.SERVICE_NOT_PRICED);
        }

        return entities;
    }

    private void populateFromExistingSnapshot(
            OrderChecklistItem entity,
            OrderChecklistItem existing,
            OrderChecklistUpdateItemRequest item,
            boolean explicitMedia,
            boolean explicitEvidence) {
        entity.setSourceChecklist(existing.getSourceChecklist());
        entity.setSourceType(existing.getSourceType());
        entity.setDescriptionSnapshot(existing.getDescriptionSnapshot());
        entity.setExecutionStepsSnapshot(existing.getExecutionStepsSnapshot() == null
                ? null
                : new ArrayList<>(existing.getExecutionStepsSnapshot()));
        entity.setInputRequirementsSnapshot(existing.getInputRequirementsSnapshot() == null
                ? null
                : new ArrayList<>(existing.getInputRequirementsSnapshot()));
        entity.setExpectedOutputsSnapshot(existing.getExpectedOutputsSnapshot() == null
                ? null
                : new ArrayList<>(existing.getExpectedOutputsSnapshot()));
        entity.setDifficultySnapshot(existing.getDifficultySnapshot());
        entity.setPricingSnapshotVersion(
                existing.getPricingSnapshotVersion() == 0 ? 1 : existing.getPricingSnapshotVersion());
        entity.setManagerQuoteRequired(existing.isManagerQuoteRequired());
        entity.setBasePriceSnapshot(
                existing.getBasePriceSnapshot() == null ? BigDecimal.ZERO : existing.getBasePriceSnapshot());
        entity.setIncludedImageCountSnapshot(existing.getIncludedImageCountSnapshot());
        entity.setIncludedVideoCountSnapshot(existing.getIncludedVideoCountSnapshot());
        entity.setPricingProfileSnapshot(existing.getPricingProfileSnapshot());
        entity.setStandardVideoDurationSnapshot(existing.getStandardVideoDurationSnapshot());
        entity.setAreaPricingEnabledSnapshot(existing.isAreaPricingEnabledSnapshot());
        entity.setLengthPricingEnabledSnapshot(existing.isLengthPricingEnabledSnapshot());
        entity.setAltitudePricingEnabledSnapshot(existing.isAltitudePricingEnabledSnapshot());
        entity.setIncludedAreaHaSnapshot(
                existing.getIncludedAreaHaSnapshot() == null ? BigDecimal.ZERO : existing.getIncludedAreaHaSnapshot());
        entity.setIncludedLengthKmSnapshot(existing.getIncludedLengthKmSnapshot() == null ? BigDecimal.ZERO
                : existing.getIncludedLengthKmSnapshot());
        entity.setIncludedAltitudeMSnapshot(existing.getIncludedAltitudeMSnapshot() == null ? BigDecimal.ZERO
                : existing.getIncludedAltitudeMSnapshot());

        boolean templateItem = existing.getSourceType() == OrderChecklistSourceType.SERVICE_TEMPLATE;
        entity.setReviewStatus(templateItem ? ChecklistReviewStatus.INCLUDED : ChecklistReviewStatus.PENDING);
        entity.setPricingStatus(
                templateItem ? ChecklistPricingStatus.AUTO_PRICED : ChecklistPricingStatus.NEEDS_MANAGER_REVIEW);

        String content = item.getContentOverride() != null ? item.getContentOverride() : existing.getContent();
        entity.setContent(ChecklistContentNormalizer.content(content));

        if (!explicitMedia && !explicitEvidence) {
            entity.setRequestedImageCount(existing.getRequestedImageCount());
            entity.setRequestedVideoCount(existing.getRequestedVideoCount());
            entity.setEvidencePolicyVersion(existing.getEvidencePolicyVersion());
            entity.setMinimumImageCount(existing.getMinimumImageCount());
            entity.setMinimumVideoCount(existing.getMinimumVideoCount());
            entity.setMinimumEvidenceCount(existing.getMinimumEvidenceCount());
            entity.setRequiredEvidenceType(existing.getRequiredEvidenceType());
            return;
        }

        int requestedImages = item.getRequestedImageCount() != null
                ? item.getRequestedImageCount()
                : (explicitEvidence ? 0 : existing.getRequestedImageCount());
        int requestedVideos = item.getRequestedVideoCount() != null
                ? item.getRequestedVideoCount()
                : (explicitEvidence ? 0 : existing.getRequestedVideoCount());

        if (explicitMedia && requestedImages + requestedVideos < 1) {
            throw invalid("A checklist needs at least one image or video");
        }
        if (explicitEvidence
                && existing.getIncludedImageCountSnapshot() + existing.getIncludedVideoCountSnapshot() > 0) {
            throw invalid("A checklist with included media allowance must specify requested media counts");
        }

        applyEvidenceAndMedia(entity, item, explicitMedia, explicitEvidence, requestedImages, requestedVideos);
    }

    private void populateNewSnapshot(
            OrderChecklistItem entity,
            ChecklistDefinition source,
            ServiceChecklistRepository.TemplateOverride override,
            OrderChecklistUpdateItemRequest item,
            Set<String> membership,
            PricingPolicy policy,
            boolean explicitMedia,
            boolean explicitEvidence) {
        boolean custom = source == null;
        boolean packageChecklist = source != null && membership.contains(source.getId());

        BigDecimal basePrice = custom ? BigDecimal.ZERO
                : (override != null && override.getBasePriceOverride() != null
                        ? override.getBasePriceOverride()
                        : source.getBasePrice());
        int includedImages = custom
                ? (policy == null ? 0 : priceCalculator.customAllowance(policy, MediaPricingComponent.INCLUDED_IMAGE))
                : (override != null && override.getIncludedImageCountOverride() != null
                        ? override.getIncludedImageCountOverride()
                        : source.getIncludedImageCount());
        int includedVideos = custom
                ? (policy == null ? 0 : priceCalculator.customAllowance(policy, MediaPricingComponent.INCLUDED_VIDEO))
                : (override != null && override.getIncludedVideoCountOverride() != null
                        ? override.getIncludedVideoCountOverride()
                        : source.getIncludedVideoCount());
        String profile = custom
                ? ChecklistPriceCalculator.GENERIC_CUSTOMER_ADDED_PROFILE
                : ChecklistPriceCalculator
                        .normalizeProfile(override != null && override.getPricingProfileOverride() != null
                                ? override.getPricingProfileOverride()
                                : source.getPricingProfile());

        int requestedImages = item.getRequestedImageCount() == null ? includedImages : item.getRequestedImageCount();
        int requestedVideos = item.getRequestedVideoCount() == null ? includedVideos : item.getRequestedVideoCount();
        if (explicitMedia && requestedImages + requestedVideos < 1) {
            throw invalid("A checklist needs at least one image or video");
        }

        entity.setPricingSnapshotVersion(1);
        entity.setManagerQuoteRequired(!packageChecklist);
        entity.setBasePriceSnapshot(basePrice == null ? BigDecimal.ZERO : basePrice);
        entity.setIncludedImageCountSnapshot(includedImages);
        entity.setIncludedVideoCountSnapshot(includedVideos);
        entity.setPricingProfileSnapshot(profile);
        entity.setStandardVideoDurationSnapshot(custom ? null : source.getStandardVideoDurationSeconds());
        entity.setAreaPricingEnabledSnapshot(!custom && source.isAreaPricingEnabled());
        entity.setLengthPricingEnabledSnapshot(!custom && source.isLengthPricingEnabled());
        entity.setAltitudePricingEnabledSnapshot(!custom && source.isAltitudePricingEnabled());
        entity.setIncludedAreaHaSnapshot(custom ? BigDecimal.ZERO : source.getIncludedAreaHa());
        entity.setIncludedLengthKmSnapshot(custom ? BigDecimal.ZERO : source.getIncludedLengthKm());
        entity.setIncludedAltitudeMSnapshot(custom ? BigDecimal.ZERO : source.getIncludedAltitudeM());

        applyEvidenceAndMedia(entity, item, explicitMedia, explicitEvidence, requestedImages, requestedVideos);

        entity.setSourceChecklist(source);
        entity.setDifficultySnapshot(source == null || source.getDifficulty() == null
                ? ChecklistDifficulty.BASIC
                : source.getDifficulty());
        entity.setSourceType(custom ? OrderChecklistSourceType.CUSTOMER_CUSTOM
                : packageChecklist ? OrderChecklistSourceType.SERVICE_TEMPLATE
                        : OrderChecklistSourceType.CUSTOMER_SELECTED);
        entity.setReviewStatus(packageChecklist ? ChecklistReviewStatus.INCLUDED : ChecklistReviewStatus.PENDING);
        entity.setPricingStatus(packageChecklist ? ChecklistPricingStatus.AUTO_PRICED
                : ChecklistPricingStatus.NEEDS_MANAGER_REVIEW);

        String content = item.getContentOverride();
        if (content == null && source != null) {
            content = source.getContent();
        }
        entity.setContent(ChecklistContentNormalizer.content(content));
        if (source != null && !custom) {
            entity.setDescriptionSnapshot(source.getDescription());
            entity.setExecutionStepsSnapshot(source.getExecutionSteps());
            entity.setInputRequirementsSnapshot(source.getInputRequirements());
            entity.setExpectedOutputsSnapshot(source.getExpectedOutputs());
        }
    }

    private void applyEvidenceAndMedia(
            OrderChecklistItem entity,
            OrderChecklistUpdateItemRequest item,
            boolean explicitMedia,
            boolean explicitEvidence,
            int requestedImages,
            int requestedVideos) {
        entity.setRequestedImageCount(requestedImages);
        entity.setRequestedVideoCount(requestedVideos);
        if (explicitMedia || (!explicitEvidence && requestedImages + requestedVideos > 0)) {
            entity.setEvidencePolicyVersion(3);
            entity.setMinimumImageCount(requestedImages);
            entity.setMinimumVideoCount(requestedVideos);
            entity.setMinimumEvidenceCount(requestedImages + requestedVideos);
            entity.setRequiredEvidenceType(requestedVideos == 0 ? RequiredEvidenceType.IMAGE
                    : requestedImages == 0 ? RequiredEvidenceType.VIDEO : RequiredEvidenceType.IMAGE_AND_VIDEO);
        } else {
            if (item.getRequiredEvidenceType() == RequiredEvidenceType.IMAGE_AND_VIDEO) {
                throw invalid(
                        "Image-and-video evidence needs requestedImageCount and requestedVideoCount, not a single minimum");
            }
            int minimumEvidenceCount = item.getMinimumEvidenceCount() == null ? 1 : item.getMinimumEvidenceCount();
            RequiredEvidenceType requiredEvidenceType = item.getRequiredEvidenceType() == null
                    ? RequiredEvidenceType.IMAGE_OR_VIDEO
                    : item.getRequiredEvidenceType();
            if (minimumEvidenceCount < 1 || minimumEvidenceCount > 50) {
                throw invalid("Checklist item '"
                        + (item.getContentOverride() == null ? "selected requirement" : item.getContentOverride())
                        + "' minimumEvidenceCount must be between 1 and 50");
            }
            entity.setEvidencePolicyVersion(2);
            entity.setMinimumImageCount(0);
            entity.setMinimumVideoCount(0);
            entity.setMinimumEvidenceCount(minimumEvidenceCount);
            entity.setRequiredEvidenceType(requiredEvidenceType);
        }
    }

    private boolean isNoOp(
            List<OrderChecklistItem> currentItems,
            List<OrderChecklistItem> candidateItems,
            List<OrderChecklistUpdateItemRequest> requested) {
        if (currentItems.size() != candidateItems.size() || currentItems.size() != requested.size()) {
            return false;
        }
        for (int i = 0; i < currentItems.size(); i++) {
            OrderChecklistItem cur = currentItems.get(i);
            OrderChecklistItem cand = candidateItems.get(i);
            OrderChecklistUpdateItemRequest req = requested.get(i);
            if (req.getExistingItemId() == null || !Objects.equals(req.getExistingItemId(), cur.getId())) {
                // Also allow matching by identical sourceChecklistId for catalog items
                String curSourceId = cur.getSourceChecklist() == null ? null : cur.getSourceChecklist().getId();
                String candSourceId = cand.getSourceChecklist() == null ? null : cand.getSourceChecklist().getId();
                if (curSourceId == null || !Objects.equals(curSourceId, candSourceId)) {
                    return false;
                }
            }
            if (cur.getSourceType() != cand.getSourceType()
                    || !Objects.equals(cur.getContent(), cand.getContent())
                    || cur.getEvidencePolicyVersion() != cand.getEvidencePolicyVersion()
                    || cur.getRequestedImageCount() != cand.getRequestedImageCount()
                    || cur.getRequestedVideoCount() != cand.getRequestedVideoCount()
                    || cur.getMinimumImageCount() != cand.getMinimumImageCount()
                    || cur.getMinimumVideoCount() != cand.getMinimumVideoCount()
                    || cur.getMinimumEvidenceCount() != cand.getMinimumEvidenceCount()
                    || cur.getRequiredEvidenceType() != cand.getRequiredEvidenceType()) {
                return false;
            }
        }
        return true;
    }

    private PricingPolicy resolveOrderPolicy(Order order) {
        if (order.getPricingPolicyId() != null && !order.getPricingPolicyId().isBlank()) {
            PricingPolicy frozen = pricingPolicies.findWithRulesById(order.getPricingPolicyId())
                    .or(() -> pricingPolicies.findById(order.getPricingPolicyId()))
                    .orElse(null);
            if (frozen != null) {
                return frozen;
            }
        }
        return pricingPolicies.findEffectiveActive(Instant.now()).stream().findFirst().orElse(null);
    }

    private String buildChangeSummary(List<OrderChecklistItem> oldItems, List<OrderChecklistItem> newItems,
            long revision) {
        return String.format("Checklist updated to revision %d (%d -> %d items)",
                revision, oldItems.size(), newItems.size());
    }

    private ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID_REQUEST, message);
    }
}
