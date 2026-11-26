package com.ondemandmonitoring.pricing.service;

import com.ondemandmonitoring.checklist.domain.ChecklistDefinition;
import com.ondemandmonitoring.checklist.domain.ServiceChecklist;
import com.ondemandmonitoring.checklist.repository.ChecklistDefinitionRepository;
import com.ondemandmonitoring.checklist.repository.ServiceChecklistRepository;
import com.ondemandmonitoring.checklist.util.ChecklistContentNormalizer;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.enums.OrderChecklistSourceType;
import com.ondemandmonitoring.order.repository.OrderChecklistItemRepository;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.pricing.domain.PricingPolicy;
import com.ondemandmonitoring.pricing.dto.ChecklistPriceBreakdown;
import com.ondemandmonitoring.pricing.dto.ChecklistPricingPreviewRequests;
import com.ondemandmonitoring.pricing.dto.ServicePricePreviewResponse;
import com.ondemandmonitoring.pricing.enums.PricingPolicyStatus;
import com.ondemandmonitoring.pricing.enums.PricingCategory;
import com.ondemandmonitoring.pricing.repository.PricingPolicyRepository;
import com.ondemandmonitoring.service.domain.Service;
import com.ondemandmonitoring.service.repository.ServiceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only price previews. Catalog checklists use the same Base Checklist formula as new order snapshots.
 */
@org.springframework.stereotype.Service
@Transactional(readOnly = true)
public class ChecklistPricingPreviewService {
    private final ServiceRepository services;
    private final ChecklistDefinitionRepository definitions;
    private final ServiceChecklistRepository assignments;
    private final PricingPolicyRepository policies;
    private final ChecklistPriceCalculator calculator;
    private final BaseChecklistPriceCalculator basePrices;
    private final OrderLevelPriceCalculator orderLevelPrices;
    private OrderRepository orders;
    private OrderChecklistItemRepository orderChecklistItems;

    @Autowired
    public ChecklistPricingPreviewService(ServiceRepository services, ChecklistDefinitionRepository definitions,
            ServiceChecklistRepository assignments, PricingPolicyRepository policies,
            ChecklistPriceCalculator calculator,
            OrderLevelPriceCalculator orderLevelPrices, BaseChecklistPriceCalculator basePrices) {
        this.services = services;
        this.definitions = definitions;
        this.assignments = assignments;
        this.policies = policies;
        this.calculator = calculator;
        this.basePrices = basePrices;
        this.orderLevelPrices = orderLevelPrices;
    }

    /** Test/backward-compatible constructor. */
    public ChecklistPricingPreviewService(ServiceRepository services, ChecklistDefinitionRepository definitions,
            ServiceChecklistRepository assignments, PricingPolicyRepository policies,
            ChecklistPriceCalculator calculator) {
        this(services, definitions, assignments, policies, calculator, new OrderLevelPriceCalculator(),
                new BaseChecklistPriceCalculator());
    }

    /** Test/backward-compatible constructor. */
    public ChecklistPricingPreviewService(ServiceRepository services, ChecklistDefinitionRepository definitions,
            ServiceChecklistRepository assignments, PricingPolicyRepository policies,
            ChecklistPriceCalculator calculator, OrderLevelPriceCalculator orderLevelPrices) {
        this(services, definitions, assignments, policies, calculator, orderLevelPrices,
                new BaseChecklistPriceCalculator());
    }

    @Autowired(required = false)
    public void setOrderRepositories(OrderRepository orders, OrderChecklistItemRepository orderChecklistItems) {
        this.orders = orders;
        this.orderChecklistItems = orderChecklistItems;
    }

    /** Default price of a service: the stored Base Checklists at their standard areas. */
    public ServicePricePreviewResponse defaultPrice(String serviceId) {
        Service service = service(serviceId);
        List<ChecklistPriceBreakdown> priced = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (ServiceChecklist row : assignments.findAllByServiceIdOrderByDisplayOrderAscIdAsc(service.getId())) {
            if (!Boolean.TRUE.equals(row.getChecklist().getIsActive())) continue;
            priced.add(priceConfiguredChecklist(null, row.getChecklist(), row.getChecklist().getBasePrice(),
                    ChecklistPriceCalculator.RequestContext.empty(), effectiveIncludedImages(row),
                    effectiveIncludedVideos(row), null, null, warnings));
        }
        BigDecimal subtotal = priced.stream().map(ChecklistPriceBreakdown::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        return response(service, null, ServicePriceResolver.of(subtotal), priced, warnings, List.of());
    }

    /** Customer estimate. Only checklist choices and quantities are read from the client; every price comes from storage. */
    public ServicePricePreviewResponse customerQuote(ChecklistPricingPreviewRequests.Customer request) {
        Order existingOrder = null;
        if (request.orderId() != null && !request.orderId().isBlank() && orders != null) {
            existingOrder = orders.findById(request.orderId()).orElse(null);
        }
        Service service = existingOrder != null && existingOrder.getService() != null
                ? existingOrder.getService() : service(request.serviceId());
        if (existingOrder == null && !Boolean.TRUE.equals(service.getIsActive())) {
            throw new ApiException(ErrorCode.SERVICE_INACTIVE);
        }
        List<OrderChecklistItem> existingOrderItems = existingOrder != null && orderChecklistItems != null
                ? orderChecklistItems.findAllByOrderIdOrderByDisplayOrderAscIdAsc(existingOrder.getId()) : List.of();
        boolean legacyOrder = existingOrderItems.stream().anyMatch(item -> item.getPricingSnapshotVersion() < 2);
        boolean hasCustomRequest = request.items() != null && request.items().stream()
                .anyMatch(item -> item.existingItemId() == null && item.sourceChecklistId() == null);
        PricingPolicy policy = null;
        if (legacyOrder || hasCustomRequest) {
            if (existingOrder != null && existingOrder.getPricingPolicyId() != null)
                policy = policies.findById(existingOrder.getPricingPolicyId()).orElse(null);
            if (policy == null) policy = activePolicy();
        }
        Map<String, ServiceChecklist> template = new LinkedHashMap<>();
        for (ServiceChecklist row : assignments.findAllByServiceIdAndChecklistIsActiveTrueOrderByDisplayOrderAscIdAsc(service.getId()))
            template.put(row.getChecklist().getId(), row);

        Map<String, OrderChecklistItem> existingItemsById = new LinkedHashMap<>();
        Set<String> requiredExistingSourceIds = new LinkedHashSet<>();
        if (!existingOrderItems.isEmpty()) {
            for (OrderChecklistItem snap : existingOrderItems) {
                existingItemsById.put(snap.getId(), snap);
                if (snap.getSourceType() == OrderChecklistSourceType.SERVICE_TEMPLATE
                        && snap.getSourceChecklist() != null
                        && Optional.ofNullable(template.get(snap.getSourceChecklist().getId())).map(ServiceChecklist::isRequired).orElse(false)) {
                    requiredExistingSourceIds.add(snap.getSourceChecklist().getId());
                }
            }
        }

        List<ChecklistPriceBreakdown> priced = new ArrayList<>();
        BigDecimal orderAreaM2 = existingOrder == null ? null : basePrices.measureSquareMeters(existingOrder.getTargetArea());
        var context = new ChecklistPriceCalculator.RequestContext(
                orderAreaM2 == null ? request.areaHa() : orderAreaM2.divide(new BigDecimal("10000"), 8, java.math.RoundingMode.HALF_UP),
                request.lengthKm(), request.altitudeM(), orderAreaM2);
        if (request.items() == null) {
            for (ServiceChecklist row : template.values())
                priced.add(priceConfiguredChecklist(policy, row.getChecklist(), row.getChecklist().getBasePrice(),
                        context, effectiveIncludedImages(row), effectiveIncludedVideos(row), null, null, null));
        } else {
            Set<String> seen = new HashSet<>();
            Set<String> contents = new HashSet<>();
            for (var item : request.items()) {
                OrderChecklistItem existingSnap = item.existingItemId() != null ? existingItemsById.get(item.existingItemId()) : null;
                if (existingSnap != null) {
                    if (existingSnap.getSourceChecklist() != null) {
                        if (!seen.add(existingSnap.getSourceChecklist().getId()))
                            throw new ApiException(ErrorCode.INVALID_REQUEST, "Duplicate source checklist");
                    } else if (existingSnap.getContent() != null) {
                        contents.add(ChecklistContentNormalizer.content(existingSnap.getContent()).toLowerCase(Locale.ROOT));
                    }
                    ChecklistPriceBreakdown calculated = existingSnap.getPricingSnapshotVersion() >= 2
                            && existingSnap.getSourceType() != OrderChecklistSourceType.CUSTOMER_CUSTOM
                                    ? priceSnapshot(existingSnap, areaM2(context), item.requestedImageCount(), item.requestedVideoCount())
                                    : priceExistingSnapshot(policy, existingSnap,
                                            item.requestedImageCount(), item.requestedVideoCount(), context);
                    priced.add(existingSnap.getSourceType() == OrderChecklistSourceType.CUSTOMER_CUSTOM
                            ? customerHidden(calculated) : calculated);
                } else if (item.sourceChecklistId() != null) {
                    ServiceChecklist row = template.get(item.sourceChecklistId());
                    if (!seen.add(item.sourceChecklistId())) throw new ApiException(ErrorCode.INVALID_REQUEST, "Duplicate source checklist");
                    ChecklistDefinition definition = row == null
                            ? definitions.findById(item.sourceChecklistId())
                                    .filter(candidate -> Boolean.TRUE.equals(candidate.getIsActive()))
                                    .orElseThrow(() -> new ApiException(ErrorCode.CHECKLIST_NOT_FOUND))
                            : row.getChecklist();
                    ChecklistPriceBreakdown calculated = row != null
                            ? priceConfiguredChecklist(policy, definition, definition.getBasePrice(), context,
                                    effectiveIncludedImages(row), effectiveIncludedVideos(row),
                                    item.requestedImageCount(), item.requestedVideoCount(), null)
                            : priceConfiguredChecklist(policy, definition, definition.getBasePrice(), context,
                                    null, null, item.requestedImageCount(), item.requestedVideoCount(), null);
                    priced.add(calculated);
                } else {
                    if (item.content() == null || item.content().isBlank())
                        throw new ApiException(ErrorCode.INVALID_REQUEST, "A custom checklist needs content");
                    String content = ChecklistContentNormalizer.content(item.content());
                    if (!contents.add(content.toLowerCase(Locale.ROOT))) throw new ApiException(ErrorCode.INVALID_REQUEST, "Duplicate checklist content");
                    ChecklistPriceBreakdown calculated = policy != null && calculator.hasCustomRule(policy)
                            ? calculator.price(policy, withContext(new ChecklistPriceCalculator.Input(null, content, null, true, null, null, null,
                                    item.requestedImageCount(), item.requestedVideoCount(), null, null, true), context))
                            : calculator.managerQuoted(content, null, item.requestedImageCount(), item.requestedVideoCount());
                    priced.add(customerHidden(calculated));
                }
            }
            if (existingOrder != null && !existingItemsById.isEmpty()) {
                for (String reqSourceId : requiredExistingSourceIds) {
                    if (!seen.contains(reqSourceId)) {
                        throw new ApiException(ErrorCode.ORDER_CHECKLIST_REQUIRED_MISSING);
                    }
                }
            } else {
                template.values().stream().filter(ServiceChecklist::isRequired)
                        .filter(row -> !seen.contains(row.getChecklist().getId())).findAny()
                        .ifPresent(row -> { throw new ApiException(ErrorCode.ORDER_CHECKLIST_REQUIRED_MISSING); });
            }
        }
        // The price source follows the service's default checklist set, exactly as at order creation.
        BigDecimal requestedImages = priced.stream().map(row -> BigDecimal.valueOf(row.requestedImageCount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal requestedVideos = priced.stream().map(row -> BigDecimal.valueOf(row.requestedVideoCount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int occurrences = legacyOrder && request.occurrences() != null
                ? Math.max(1, Math.min(PricingOccurrences.MAX, request.occurrences())) : 1;
        if (legacyOrder && occurrences > 1) {
            priced = priced.stream().map(row -> row.times(occurrences)).toList();
        }
        BigDecimal checklistSubtotal = priced.stream().map(ChecklistPriceBreakdown::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<ServicePricePreviewResponse.AddOn> addOns = legacyOrder ? orderLevelPrices.calculate(policy,
                        OrderLevelPriceCalculator.Context.preview(request.resultFormats(), requestedImages, requestedVideos,
                                request.permitStatus()), checklistSubtotal, BigDecimal.ZERO).stream()
                .filter(charge -> charge.line().amount().signum() != 0)
                .map(charge -> new ServicePricePreviewResponse.AddOn(charge.key(), customerDescription(charge),
                        charge.line().amount()))
                .toList() : List.of();
        return response(service, policy, ServicePriceResolver.of(checklistSubtotal), priced, List.of(), addOns, occurrences);
    }

    private ChecklistPriceBreakdown priceExistingSnapshot(PricingPolicy policy, OrderChecklistItem snap,
            Integer requestedImages, Integer requestedVideos, ChecklistPriceCalculator.RequestContext context) {
        if (snap.getSourceType() == OrderChecklistSourceType.CUSTOMER_CUSTOM) {
            return calculator.hasCustomRule(policy)
                    ? calculator.price(policy, withContext(new ChecklistPriceCalculator.Input(null, snap.getContent(), null, true, null, null, null,
                            requestedImages, requestedVideos, null, null, true), context))
                    : calculator.managerQuoted(snap.getContent(), null, requestedImages, requestedVideos);
        }
        String checklistId = snap.getSourceChecklist() != null ? snap.getSourceChecklist().getId() : snap.getId();
        Integer standardVideoSec = snap.getSourceChecklist() != null ? snap.getSourceChecklist().getStandardVideoDurationSeconds() : null;
        var dims = new ChecklistPriceCalculator.PricingDimensions(
                snap.isAreaPricingEnabledSnapshot(), snap.isLengthPricingEnabledSnapshot(),
                snap.isAltitudePricingEnabledSnapshot(), snap.getIncludedAreaHaSnapshot(),
                snap.getIncludedLengthKmSnapshot(), snap.getIncludedAltitudeMSnapshot());
        var in = new ChecklistPriceCalculator.Input(checklistId, snap.getContent(), snap.getDifficultySnapshot(), false,
                snap.getBasePriceSnapshot(), snap.getIncludedImageCountSnapshot(), snap.getIncludedVideoCountSnapshot(),
                requestedImages, requestedVideos, snap.getPricingProfileSnapshot(),
                standardVideoSec, true, dims, context);
        return calculator.price(policy, in);
    }

    private ChecklistPriceBreakdown priceConfiguredChecklist(PricingPolicy policy, ChecklistDefinition definition,
            BigDecimal basePrice, ChecklistPriceCalculator.RequestContext context,
            Integer includedImagesOverride, Integer includedVideosOverride,
            Integer requestedImages, Integer requestedVideos, List<String> warnings) {
        BigDecimal actualAreaM2 = areaM2(context);
        // No area yet (e.g. the customer is still on the service step): quote at the standard
        // area, i.e. without an area surcharge. An explicit zero/negative area is still rejected.
        if (actualAreaM2 == null) actualAreaM2 = definition.getStandardAreaM2();
        if (actualAreaM2 == null || actualAreaM2.signum() <= 0) {
            if (warnings == null) throw new ApiException(ErrorCode.PRICING_CALCULATION_FAILED, "Actual area must be greater than zero");
            warnings.add(definition.getContent() + ": Actual area must be greater than zero");
            return emptyAreaBreakdown(definition, basePrice);
        }
        try {
            ChecklistPriceBreakdown areaPrice = basePrices.breakdown(definition.getId(), definition.getContent(),
                    basePrices.calculate(basePrice, definition.getStandardAreaM2(), actualAreaM2));
            int includedImages = includedImagesOverride == null
                    ? definition.getIncludedImageCount() : includedImagesOverride;
            int includedVideos = includedVideosOverride == null
                    ? definition.getIncludedVideoCount() : includedVideosOverride;
            int imageCount = requestedImages == null ? includedImages : requestedImages;
            int videoCount = requestedVideos == null ? includedVideos : requestedVideos;
            return basePrices.withMediaCharges(areaPrice, includedImages,
                    includedVideos, imageCount, videoCount,
                    definition.getExtraImageUnitPrice(), definition.getExtraVideoUnitPrice());
        } catch (ApiException exception) {
            if (warnings == null) throw exception;
            warnings.add(definition.getContent() + ": " + exception.getMessage());
            return emptyAreaBreakdown(definition, basePrice);
        }
    }

    private ChecklistPriceBreakdown priceSnapshot(OrderChecklistItem snapshot, BigDecimal actualAreaM2,
            Integer requestedImages, Integer requestedVideos) {
        ChecklistPriceBreakdown areaPrice = basePrices.breakdown(
                snapshot.getSourceChecklist() == null ? snapshot.getId() : snapshot.getSourceChecklist().getId(),
                snapshot.getContent(), basePrices.calculate(snapshot.getBasePriceSnapshot(),
                        snapshot.getSourceChecklist() == null ? null : snapshot.getSourceChecklist().getStandardAreaM2(),
                        actualAreaM2));
        int imageCount = requestedImages == null ? snapshot.getRequestedImageCount() : requestedImages;
        int videoCount = requestedVideos == null ? snapshot.getRequestedVideoCount() : requestedVideos;
        return basePrices.withMediaCharges(areaPrice, snapshot.getIncludedImageCountSnapshot(),
                snapshot.getIncludedVideoCountSnapshot(), imageCount, videoCount,
                snapshot.getExtraImageUnitPriceSnapshot(), snapshot.getExtraVideoUnitPriceSnapshot());
    }

    private ChecklistPriceBreakdown emptyAreaBreakdown(ChecklistDefinition definition, BigDecimal basePrice) {
        return new ChecklistPriceBreakdown(definition.getId(), definition.getContent(), null, false,
                0, 0, 0, 0, 0, 0, null, List.of(), BigDecimal.ZERO, false,
                basePrice, definition.getStandardAreaM2(), null, null);
    }

    private int effectiveIncludedImages(ServiceChecklist row) {
        return row.getIncludedImageCountOverride() == null
                ? row.getChecklist().getIncludedImageCount() : row.getIncludedImageCountOverride();
    }

    private int effectiveIncludedVideos(ServiceChecklist row) {
        return row.getIncludedVideoCountOverride() == null
                ? row.getChecklist().getIncludedVideoCount() : row.getIncludedVideoCountOverride();
    }

    private BigDecimal areaM2(ChecklistPriceCalculator.RequestContext context) {
        return context.areaM2() != null ? context.areaM2()
                : context.areaHa() == null ? null : context.areaHa().multiply(new BigDecimal("10000"));
    }

    private String customerDescription(OrderLevelPriceCalculator.Charge charge) {
        if (charge.line().category() == PricingCategory.OUTPUT) return switch (charge.key()) {
            case "OUTPUT_VIDEO" -> "Video bàn giao";
            case "OUTPUT_PHOTO" -> "Ảnh bàn giao";
            case "OUTPUT_PDF_REPORT" -> "Báo cáo PDF";
            case "OUTPUT_ORTHOMOSAIC" -> "Ảnh trực giao ghép";
            case "OUTPUT_MODEL_3D" -> "Mô hình 3D";
            case "OUTPUT_HIGH_RESOLUTION_IMAGE" -> "Ảnh độ phân giải cao";
            case "OUTPUT_4K_VIDEO" -> "Video 4K bàn giao";
            default -> charge.line().description();
        };
        return charge.line().description();
    }

    /** Public customer previews disclose neither the internal amount nor its component breakdown. */
    private ChecklistPriceBreakdown customerHidden(ChecklistPriceBreakdown priced) {
        return new ChecklistPriceBreakdown(priced.checklistId(), priced.content(), priced.pricingProfile(), priced.custom(),
                priced.includedImageCount(), priced.includedVideoCount(), priced.requestedImageCount(),
                priced.requestedVideoCount(), priced.extraImageCount(), priced.extraVideoCount(),
                priced.standardVideoDurationSeconds(), List.of(), BigDecimal.ZERO, true);
    }

    private ChecklistPriceCalculator.Input withContext(ChecklistPriceCalculator.Input input,
            ChecklistPriceCalculator.RequestContext context) {
        return new ChecklistPriceCalculator.Input(input.checklistId(), input.content(), input.difficulty(), input.custom(),
                input.catalogBasePrice(), input.includedImages(), input.includedVideos(), input.requestedImages(),
                input.requestedVideos(), input.pricingProfile(), input.standardVideoDurationSeconds(), input.includeDifficulty(),
                input.dimensions(), context);
    }

    private ServicePricePreviewResponse response(Service service, PricingPolicy policy, ServicePriceResolver.Resolved resolved,
            List<ChecklistPriceBreakdown> priced, List<String> warnings, List<ServicePricePreviewResponse.AddOn> addOns) {
        return response(service, policy, resolved, priced, warnings, addOns, 1);
    }

    private ServicePricePreviewResponse response(Service service, PricingPolicy policy, ServicePriceResolver.Resolved resolved,
            List<ChecklistPriceBreakdown> priced, List<String> warnings, List<ServicePricePreviewResponse.AddOn> addOns,
            int occurrences) {
        BigDecimal subtotal = priced.stream().map(ChecklistPriceBreakdown::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal addOnTotal = addOns.stream().map(ServicePricePreviewResponse.AddOn::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fee = resolved.packageFee();
        return new ServicePricePreviewResponse(service.getId(), service.getName(), policy == null ? "VND" : policy.getCurrency(),
                policy == null ? null : policy.getId(), policy == null ? 0 : policy.getPolicyVersion(),
                policy != null && policy.getStatus() == PricingPolicyStatus.DRAFT,
                resolved.source(), fee, List.copyOf(priced), subtotal, fee.add(subtotal).add(addOnTotal),
                warnings.isEmpty(), List.copyOf(warnings), priced.stream().anyMatch(ChecklistPriceBreakdown::quotePending),
                List.copyOf(addOns), occurrences);
    }

    private PricingPolicy activePolicy() {
        return policies.findEffectiveActive(Instant.now()).stream().findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.PRICING_POLICY_NOT_FOUND));
    }
    private Service service(String id) {
        return services.findById(id).orElseThrow(() -> new ApiException(ErrorCode.SERVICE_NOT_FOUND));
    }
}
