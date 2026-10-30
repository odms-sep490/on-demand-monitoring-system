package com.ondemandmonitoring.order.service.impl;

import com.ondemandmonitoring.checklist.domain.ChecklistDefinition;
import com.ondemandmonitoring.checklist.repository.ChecklistDefinitionRepository;
import com.ondemandmonitoring.checklist.repository.ServiceChecklistRepository;
import com.ondemandmonitoring.checklist.util.ChecklistContentNormalizer;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.domain.OrderChecklistRevision;
import com.ondemandmonitoring.order.dto.request.OrderChecklistItemRequest;
import com.ondemandmonitoring.order.dto.response.CustomerOrderChecklistItemResponse;
import com.ondemandmonitoring.order.dto.response.OrderCreateResponse;
import com.ondemandmonitoring.order.enums.ChecklistPricingStatus;
import com.ondemandmonitoring.order.enums.ChecklistReviewStatus;
import com.ondemandmonitoring.order.enums.ExecutionPlanStatus;
import com.ondemandmonitoring.order.enums.OrderChecklistSourceType;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.enums.RequiredEvidenceType;
import com.ondemandmonitoring.order.mapper.OrderChecklistItemMapper;
import com.ondemandmonitoring.order.repository.OrderChecklistItemRepository;
import com.ondemandmonitoring.order.repository.OrderChecklistRevisionRepository;
import com.ondemandmonitoring.order.service.IOrderChecklistSnapshotService;
import com.ondemandmonitoring.pricing.domain.PricingPolicy;
import com.ondemandmonitoring.pricing.enums.ChecklistDifficulty;
import com.ondemandmonitoring.pricing.enums.MediaPricingComponent;
import com.ondemandmonitoring.pricing.repository.PricingPolicyRepository;
import com.ondemandmonitoring.pricing.service.ChecklistPriceCalculator;
import com.ondemandmonitoring.pricing.service.PricingEngine;
import com.ondemandmonitoring.pricing.service.ServicePriceResolver;
import com.ondemandmonitoring.service.repository.ServiceRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderChecklistSnapshotService implements IOrderChecklistSnapshotService {
    private final ServiceRepository services;
    private final ChecklistDefinitionRepository definitions;
    private final ServiceChecklistRepository assignments;
    private final OrderChecklistItemRepository snapshots;
    private final OrderChecklistItemMapper mapper;
    private final AuthenticatedUserResolver currentUser;
    private final PricingPolicyRepository pricingPolicies;
    private final ChecklistPriceCalculator priceCalculator;
    private final ServicePriceResolver servicePrices;
    private PricingEngine pricingEngine;
    private OrderChecklistRevisionRepository revisionRepository;
    private OrderChecklistEditPolicy editPolicy;

    @Autowired(required = false)
    public void setPricingEngine(PricingEngine pricingEngine) {
        this.pricingEngine = pricingEngine;
    }

    @Autowired(required = false)
    public void setRevisionRepository(OrderChecklistRevisionRepository revisionRepository) {
        this.revisionRepository = revisionRepository;
    }

    @Autowired(required = false)
    public void setEditPolicy(OrderChecklistEditPolicy editPolicy) {
        this.editPolicy = editPolicy;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<CustomerOrderChecklistItemResponse> capture(Order order, List<OrderChecklistItemRequest> requested) {
        if (order.getChecklistSnapshotAt() != null || order.getOrderStatus() != OrderStatus.PENDING) {
            throw new ApiException(ErrorCode.ORDER_CHECKLIST_LOCKED);
        }
        if (order.getCustomer() == null
                || !Objects.equals(order.getCustomer().getId(), currentUser.getCurrentUser().getId())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED);
        }
        var service = services.findByIdForUpdate(order.getService().getId())
                .orElseThrow(() -> new ApiException(ErrorCode.SERVICE_NOT_FOUND));
        if (!Boolean.TRUE.equals(service.getIsActive())) throw new ApiException(ErrorCode.SERVICE_INACTIVE);
        // Read IDs only: loading catalog entities before locking can leave stale
        // content/version in the persistence context after a concurrent edit.
        List<String> template = assignments.findTemplateChecklistIds(service.getId());
        Set<String> membership = new HashSet<>(template);
        List<OrderChecklistItemRequest> items = requested;
        boolean defaults = items == null;
        if (defaults) {
            items = template.stream().map(id -> {
                var item = new OrderChecklistItemRequest();
                item.setSourceChecklistId(id);
                return item;
            }).toList();
        }
        if (!defaults && items.size() > 100) throw invalid("At most 100 checklist items are allowed");
        Map<String, ServiceChecklistRepository.TemplateOverride> overrides = new HashMap<>();
        Set<String> requiredChecklistIds = new HashSet<>();
        for (var override : assignments.findTemplateOverrides(service.getId())) {
            overrides.put(override.getChecklistId(), override);
            if (override.isRequired() && Boolean.TRUE.equals(override.getChecklistActive())) {
                requiredChecklistIds.add(override.getChecklistId());
            }
        }
        Set<String> ids = new TreeSet<>();
        for (var item : items) {
            if (item == null) throw invalid("Checklist item cannot be null");
            String source = item.getSourceChecklistId();
            if (source != null) {
                if (source.isBlank())
                    throw new ApiException(ErrorCode.ORDER_CHECKLIST_TEMPLATE_CHANGED);
                if (!ids.add(source)) throw invalid("Duplicate source checklist");
                if (!defaults && (item.getExpectedChecklistVersion() == null || item.getExpectedChecklistVersion() < 0))
                    throw invalid("expectedChecklistVersion is required for a template item");
            } else if (item.getExpectedChecklistVersion() != null) throw invalid("Custom item cannot declare a catalog version");
        }
        if (!defaults) {
            for (String reqId : requiredChecklistIds) {
                if (!ids.contains(reqId))
                    throw new ApiException(ErrorCode.ORDER_CHECKLIST_REQUIRED_MISSING);
            }
        }
        Map<String, ChecklistDefinition> locked = new HashMap<>();
        for (String id : ids) locked.put(id, definitions.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_CHECKLIST_TEMPLATE_CHANGED)));
        List<OrderChecklistItem> entities = new ArrayList<>();
        Set<String> contents = new HashSet<>();
        PricingPolicy policy = pricingPolicies.findEffectiveActive(Instant.now()).stream().findFirst().orElse(null);
        if (policy != null) {
            order.setPricingPolicyId(policy.getId());
            order.setPricingPolicyVersion(policy.getPolicyVersion());
        }
        BigDecimal pricedTotal = BigDecimal.ZERO;
        for (var item : items) {
            ChecklistDefinition source = locked.get(item.getSourceChecklistId());
            if (source != null && !Boolean.TRUE.equals(source.getIsActive())) {
                if (defaults) continue;
                throw new ApiException(ErrorCode.ORDER_CHECKLIST_TEMPLATE_CHANGED);
            }
            if (source != null && !defaults && !Objects.equals(source.getVersion(), item.getExpectedChecklistVersion()))
                throw new ApiException(ErrorCode.ORDER_CHECKLIST_TEMPLATE_CHANGED);
            var override = source == null ? null : overrides.get(source.getId());
            boolean custom = source == null;
            boolean packageChecklist = source != null && membership.contains(source.getId());
            boolean explicitEvidence = item.getMinimumEvidenceCount() != null || item.getRequiredEvidenceType() != null;
            boolean explicitMedia = item.getRequestedImageCount() != null || item.getRequestedVideoCount() != null;
            if (explicitEvidence && explicitMedia)
                throw invalid("Use requested media counts or an explicit evidence requirement, not both");
            if ((item.getRequestedImageCount() != null && (item.getRequestedImageCount() < 0 || item.getRequestedImageCount() > 500))
                    || (item.getRequestedVideoCount() != null && (item.getRequestedVideoCount() < 0 || item.getRequestedVideoCount() > 500))) {
                throw invalid("Requested media counts must be between 0 and 500");
            }
            // Effective configuration = association override, else the checklist definition (custom: policy allowance).
            BigDecimal basePrice = custom ? BigDecimal.ZERO : (override != null && override.getBasePriceOverride() != null
                    ? override.getBasePriceOverride() : source.getBasePrice());
            int includedImages = custom ? (policy == null ? 0 : priceCalculator.customAllowance(policy, MediaPricingComponent.INCLUDED_IMAGE))
                    : (override != null && override.getIncludedImageCountOverride() != null
                    ? override.getIncludedImageCountOverride() : source.getIncludedImageCount());
            int includedVideos = custom ? (policy == null ? 0 : priceCalculator.customAllowance(policy, MediaPricingComponent.INCLUDED_VIDEO))
                    : (override != null && override.getIncludedVideoCountOverride() != null
                    ? override.getIncludedVideoCountOverride() : source.getIncludedVideoCount());
            String profile = custom ? ChecklistPriceCalculator.GENERIC_CUSTOMER_ADDED_PROFILE : ChecklistPriceCalculator.normalizeProfile(
                    override != null && override.getPricingProfileOverride() != null
                            ? override.getPricingProfileOverride() : source.getPricingProfile());
            int requestedImages = item.getRequestedImageCount() == null ? includedImages : item.getRequestedImageCount();
            int requestedVideos = item.getRequestedVideoCount() == null ? includedVideos : item.getRequestedVideoCount();
            if (explicitMedia && requestedImages + requestedVideos < 1)
                throw invalid("A checklist needs at least one image or video");
            var entity = new OrderChecklistItem();
            entity.setChecklistRevision(1);
            entity.setPricingSnapshotVersion(1);
            // Customer-added work is priced by policy first, then explicitly reviewed by a Manager.
            entity.setManagerQuoteRequired(!packageChecklist);
            entity.setBasePriceSnapshot(basePrice == null ? BigDecimal.ZERO : basePrice);
            entity.setIncludedImageCountSnapshot(includedImages);
            entity.setIncludedVideoCountSnapshot(includedVideos);
            entity.setRequestedImageCount(requestedImages);
            entity.setRequestedVideoCount(requestedVideos);
            entity.setPricingProfileSnapshot(profile);
            entity.setStandardVideoDurationSnapshot(custom ? null : source.getStandardVideoDurationSeconds());
            entity.setAreaPricingEnabledSnapshot(!custom && source.isAreaPricingEnabled());
            entity.setLengthPricingEnabledSnapshot(!custom && source.isLengthPricingEnabled());
            entity.setAltitudePricingEnabledSnapshot(!custom && source.isAltitudePricingEnabled());
            entity.setIncludedAreaHaSnapshot(custom ? BigDecimal.ZERO : source.getIncludedAreaHa());
            entity.setIncludedLengthKmSnapshot(custom ? BigDecimal.ZERO : source.getIncludedLengthKm());
            entity.setIncludedAltitudeMSnapshot(custom ? BigDecimal.ZERO : source.getIncludedAltitudeM());
            if (explicitMedia || (!explicitEvidence && requestedImages + requestedVideos > 0)) {
                // Evidence policy V3: acceptance needs the requested images AND the requested videos.
                entity.setEvidencePolicyVersion(3);
                entity.setMinimumImageCount(requestedImages);
                entity.setMinimumVideoCount(requestedVideos);
                entity.setMinimumEvidenceCount(requestedImages + requestedVideos);
                entity.setRequiredEvidenceType(requestedVideos == 0 ? RequiredEvidenceType.IMAGE
                        : requestedImages == 0 ? RequiredEvidenceType.VIDEO : RequiredEvidenceType.IMAGE_AND_VIDEO);
            } else {
                if (item.getRequiredEvidenceType() == RequiredEvidenceType.IMAGE_AND_VIDEO)
                    throw invalid("Image-and-video evidence needs requestedImageCount and requestedVideoCount, not a single minimum");
                int minimumEvidenceCount = item.getMinimumEvidenceCount() == null ? 1 : item.getMinimumEvidenceCount();
                RequiredEvidenceType requiredEvidenceType = item.getRequiredEvidenceType() == null
                        ? RequiredEvidenceType.IMAGE_OR_VIDEO : item.getRequiredEvidenceType();
                if (minimumEvidenceCount < 1 || minimumEvidenceCount > 50) {
                    throw invalid("Checklist item '" + (item.getContentOverride() == null ? "selected requirement" : item.getContentOverride()) + "' minimumEvidenceCount must be between 1 and 50");
                }
                entity.setEvidencePolicyVersion(2);
                entity.setMinimumEvidenceCount(minimumEvidenceCount);
                entity.setRequiredEvidenceType(requiredEvidenceType);
            }
            entity.setOrder(order);
            entity.setSourceChecklist(source);
            entity.setDifficultySnapshot(source == null || source.getDifficulty() == null
                    ? ChecklistDifficulty.BASIC : source.getDifficulty());
            entity.setSourceType(custom ? OrderChecklistSourceType.CUSTOMER_CUSTOM
                    : packageChecklist ? OrderChecklistSourceType.SERVICE_TEMPLATE : OrderChecklistSourceType.CUSTOMER_SELECTED);
            entity.setReviewStatus(packageChecklist ? ChecklistReviewStatus.INCLUDED : ChecklistReviewStatus.PENDING);
            entity.setPricingStatus(packageChecklist ? ChecklistPricingStatus.AUTO_PRICED
                    : ChecklistPricingStatus.NEEDS_MANAGER_REVIEW);
            String content = item.getContentOverride();
            if (content == null && source != null) {
                content = source.getContent();
            }
            entity.setContent(ChecklistContentNormalizer.content(content));
            if (source != null && !custom) {
                // Customer-edited text may no longer match the catalog wording, but the guidance still describes the work.
                entity.setDescriptionSnapshot(source.getDescription());
                entity.setExecutionStepsSnapshot(source.getExecutionSteps());
                entity.setInputRequirementsSnapshot(source.getInputRequirements());
                entity.setExpectedOutputsSnapshot(source.getExpectedOutputs());
            }
            if (!contents.add(entity.getContent().toLowerCase(Locale.ROOT))) throw invalid("Duplicate checklist content");
            entity.setDisplayOrder(entities.size());
            // Fail fast: a selection the policy cannot price must not become a snapshot.
            if (policy != null) pricedTotal = pricedTotal.add(priceCalculator.price(policy, new ChecklistPriceCalculator.Input(
                    source == null ? null : source.getId(), entity.getContent(), entity.getDifficultySnapshot(), custom,
                    entity.getBasePriceSnapshot(), custom ? null : includedImages, custom ? null : includedVideos,
                    requestedImages, requestedVideos, profile, entity.getStandardVideoDurationSnapshot(), false)).subtotal());
            entities.add(entity);
        }
        if (entities.size() > 100) throw invalid("At most 100 checklist items are allowed");
        // The checklists are the whole price: an order whose checklists add up to nothing on a service that has no priced
        // default checklists would be free, so it is refused. (Customer-added checklists awaiting a Manager quote are priced by the Manager.)
        if (policy != null && pricedTotal.signum() == 0
                && ServicePriceResolver.UNPRICED.equals(servicePrices.resolveOrLegacy(service).source())
                && entities.stream().noneMatch(e -> e.getSourceType() == OrderChecklistSourceType.CUSTOMER_CUSTOM))
            throw new ApiException(ErrorCode.SERVICE_NOT_PRICED);
        order.setServiceBasePriceSnapshot(null);
        List<OrderChecklistItem> saved = snapshots.saveAllAndFlush(entities);
        applySystemPrices(order, saved);
        order.setChecklistSnapshotAt(Instant.now());
        order.setChecklistRevision(1);
        if (revisionRepository != null) {
            OrderChecklistRevision rev = new OrderChecklistRevision();
            rev.setOrder(order);
            rev.setRevision(1);
            rev.setActor(order.getCustomer());
            rev.setChangeSummary("Initial checklist snapshot (" + saved.size() + " items)");
            rev.setPayloadFingerprint(fingerprint(saved));
            revisionRepository.save(rev);
        }
        return saved.stream().map(row -> {
            CustomerOrderChecklistItemResponse dto = mapper.toCustomerResponse(row);
            if (dto.getSourceChecklistId() != null && requiredChecklistIds.contains(dto.getSourceChecklistId())) {
                dto.setRequired(true);
            }
            return dto;
        }).toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recalculate(Order order) {
        applySystemPrices(order, snapshots.findAllByOrderIdOrderByDisplayOrderAscIdAsc(order.getId()));
    }

    public void applySystemPrices(Order order, List<OrderChecklistItem> items) {
        if (pricingEngine == null) return;
        if (items.isEmpty()) {
            order.setSystemCalculatedAmount(BigDecimal.ZERO);
            return;
        }
        var calculated = pricingEngine.calculate(order, items);
        order.setSystemCalculatedAmount(calculated.systemCalculatedAmount());
        Map<String, BigDecimal> calculatedByItem = new HashMap<>();
        calculated.items().stream().filter(line -> line.orderChecklistItemId() != null)
                .forEach(line -> calculatedByItem.merge(line.orderChecklistItemId(), line.amount(), BigDecimal::add));
        items.forEach(item -> item.setSystemCalculatedPrice(
                calculatedByItem.getOrDefault(item.getId(), BigDecimal.ZERO)));
    }

    @Override
    public void enrichOrderResponse(Order order, OrderCreateResponse response, boolean includeItemRequiredMetadata) {
        if (order == null || response == null) return;
        response.setChecklistRevision(order.getChecklistRevision());
        OrderChecklistEditPolicy policy = editPolicy != null
                ? editPolicy
                : new OrderChecklistEditPolicy(null, null, null, null, null);
        OrderChecklistEditPolicy.EditCapability capability = policy.evaluate(order);
        response.setCanEditChecklist(capability.canEdit());
        response.setChecklistEditBlockedReason(capability.blockedReason());

        if (includeItemRequiredMetadata
                && order.getExecutionPlanStatus() != ExecutionPlanStatus.FINALIZED
                && order.getExecutionPlanRevision() == 0
                && response.getChecklistItems() != null
                && !response.getChecklistItems().isEmpty()
                && order.getService() != null
                && order.getService().getId() != null) {
            Set<String> requiredIds = assignments.findTemplateOverrides(order.getService().getId()).stream()
                    .filter(o -> o.isRequired() && Boolean.TRUE.equals(o.getChecklistActive()))
                    .map(ServiceChecklistRepository.TemplateOverride::getChecklistId)
                    .collect(Collectors.toSet());
            for (CustomerOrderChecklistItemResponse item : response.getChecklistItems()) {
                item.setRequired(item.getSourceChecklistId() != null && requiredIds.contains(item.getSourceChecklistId()));
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, List<CustomerOrderChecklistItemResponse>> getByOrders(List<String> orderIds) {
        if (orderIds.isEmpty()) return Map.of();
        return snapshots.findAllByOrderIdInOrderByDisplayOrderAscIdAsc(orderIds).stream()
                .collect(Collectors.groupingBy(item -> item.getOrder().getId(),
                        Collectors.mapping(mapper::toCustomerResponse, Collectors.toList())));
    }

    public static String fingerprint(List<OrderChecklistItem> items) {
        StringBuilder sb = new StringBuilder();
        for (OrderChecklistItem item : items) {
            sb.append(item.getSourceChecklist() == null ? "CUSTOM" : item.getSourceChecklist().getId())
              .append('|').append(item.getSourceType())
              .append('|').append(item.getContent())
              .append('|').append(item.getEvidencePolicyVersion())
              .append('|').append(item.getRequestedImageCount())
              .append('|').append(item.getRequestedVideoCount())
              .append('|').append(item.getMinimumEvidenceCount())
              .append('|').append(item.getRequiredEvidenceType())
              .append('\n');
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID_REQUEST, message);
    }
}
