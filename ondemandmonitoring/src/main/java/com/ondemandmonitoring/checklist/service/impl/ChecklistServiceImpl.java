package com.ondemandmonitoring.checklist.service.impl;

import com.ondemandmonitoring.checklist.domain.ChecklistDefinition;
import com.ondemandmonitoring.checklist.dto.request.ChecklistRequest;
import com.ondemandmonitoring.checklist.dto.response.ChecklistResponse;
import com.ondemandmonitoring.checklist.mapper.ChecklistMapper;
import com.ondemandmonitoring.checklist.repository.ChecklistDefinitionRepository;
import com.ondemandmonitoring.checklist.service.IChecklistService;
import com.ondemandmonitoring.common.api.PageResponse;
import com.ondemandmonitoring.common.exception.*;
import static com.ondemandmonitoring.checklist.util.ChecklistContentNormalizer.content;
import static com.ondemandmonitoring.checklist.util.ChecklistContentNormalizer.canonicalize;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChecklistServiceImpl implements IChecklistService {
    private static final Set<String> SORT_FIELDS = Set.of("id", "content", "isActive", "createdAt", "updatedAt");
    private final ChecklistDefinitionRepository repository;
    private final ChecklistMapper mapper;

    private com.ondemandmonitoring.pricing.service.ServiceDefaultPriceService defaultPrices;

    private com.ondemandmonitoring.checklist.service.ChecklistAuditService audit;
    private com.ondemandmonitoring.checklist.repository.ServiceChecklistRepository serviceUsage;
    private com.ondemandmonitoring.order.repository.OrderChecklistItemRepository orderUsage;

    /** Optional collaborators: change history and the "used by" counts. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAudit(com.ondemandmonitoring.checklist.service.ChecklistAuditService audit) {
        this.audit = audit;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setUsage(com.ondemandmonitoring.checklist.repository.ServiceChecklistRepository serviceUsage,
            com.ondemandmonitoring.order.repository.OrderChecklistItemRepository orderUsage) {
        this.serviceUsage = serviceUsage;
        this.orderUsage = orderUsage;
    }

    /** Adds the "used by" counts to a page of responses with two grouped queries. */
    private java.util.List<ChecklistResponse> withUsage(java.util.List<ChecklistResponse> responses) {
        if (responses.isEmpty() || serviceUsage == null || orderUsage == null) return responses;
        var ids = responses.stream().map(ChecklistResponse::getId).toList();
        var services = new java.util.HashMap<String, Long>();
        serviceUsage.countByChecklistIds(ids).forEach(row -> services.put(row.getChecklistId(), row.getTotal()));
        var orders = new java.util.HashMap<String, Long>();
        orderUsage.countBySourceChecklistIds(ids).forEach(row -> orders.put(row.getChecklistId(), row.getTotal()));
        responses.forEach(response -> {
            response.setUsedByServiceCount(services.getOrDefault(response.getId(), 0L));
            response.setUsedByOrderCount(orders.getOrDefault(response.getId(), 0L));
        });
        return responses;
    }

    private ChecklistResponse withUsage(ChecklistResponse response) {
        withUsage(java.util.List.of(response));
        return response;
    }

    /** Optional collaborator: keeps the stored service default price current. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDefaultPrices(com.ondemandmonitoring.pricing.service.ServiceDefaultPriceService defaultPrices) {
        this.defaultPrices = defaultPrices;
    }

    @Override
    @Transactional
    public ChecklistResponse create(ChecklistRequest request) {
        String content = content(request.getContent());
        String normalized = content.toLowerCase(Locale.ROOT);
        if (repository.existsByNormalizedContent(normalized)) {
            throw new ApiException(ErrorCode.CHECKLIST_ALREADY_EXISTS);
        }
        ChecklistDefinition entity = mapper.toEntity(request);
        entity.setContent(content);
        entity.setNormalizedContent(normalized);
        entity.setDifficulty(request.getDifficulty() == null
                ? com.ondemandmonitoring.pricing.enums.ChecklistDifficulty.BASIC : request.getDifficulty());
        applyPricing(entity, request, true);
        applyGuidance(entity, request);
        var created = repository.saveAndFlush(entity);
        if (audit != null) audit.record(com.ondemandmonitoring.checklist.enums.ChecklistConfigAuditAction.CHECKLIST_CREATED,
                created, null, java.util.Map.of(), audit.snapshot(created));
        return withUsage(mapper.toResponse(created));
    }

    @Override
    public ChecklistResponse getById(String id) {
        return withUsage(mapper.toResponse(repository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.CHECKLIST_NOT_FOUND))));
    }

    @Override
    public PageResponse<ChecklistResponse> getAll(String search, Boolean active, Pageable pageable) {
        if (pageable.getPageSize() > 100) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Page size must not exceed 100");
        }
        pageable.getSort().forEach(order -> {
            if (!SORT_FIELDS.contains(order.getProperty())) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "Unsupported checklist sort property: " + order.getProperty());
            }
        });
        Sort sort = pageable.getSort().isSorted() ? pageable.getSort() : Sort.by(Sort.Direction.DESC, "createdAt");
        if (sort.getOrderFor("id") == null) sort = sort.and(Sort.by("id"));
        String term = search == null ? "" : canonicalize(search).toLowerCase(Locale.ROOT);
        String escaped = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        Specification<ChecklistDefinition> filter = (root, query, cb) -> {
            var predicate = cb.conjunction();
            if (active != null) predicate = cb.and(predicate, cb.equal(root.get("isActive"), active));
            if (!term.isBlank()) predicate = cb.and(predicate,
                    cb.like(root.get("normalizedContent"), "%" + escaped + "%", '\\'));
            return predicate;
        };
        var found = repository.findAll(filter, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort));
        var responses = withUsage(found.getContent().stream().map(mapper::toResponse).toList());
        return PageResponse.from(new PageImpl<>(responses, found.getPageable(), found.getTotalElements()));
    }

    @Override
    @Transactional
    public ChecklistResponse update(String id, ChecklistRequest request) {
        ChecklistDefinition entity = locked(id);
        String content = content(request.getContent());
        String normalized = content.toLowerCase(Locale.ROOT);
        if (repository.existsByNormalizedContentAndIdNot(normalized, id)) {
            throw new ApiException(ErrorCode.CHECKLIST_ALREADY_EXISTS);
        }
        var before = audit == null ? null : audit.snapshot(entity);
        entity.setContent(content);
        entity.setNormalizedContent(normalized);
        if (request.getDifficulty() != null) entity.setDifficulty(request.getDifficulty());
        applyPricing(entity, request, false);
        applyGuidance(entity, request);
        var saved = repository.saveAndFlush(entity);
        if (audit != null) audit.record(com.ondemandmonitoring.checklist.enums.ChecklistConfigAuditAction.CHECKLIST_UPDATED,
                saved, null, before, audit.snapshot(saved));
        if (defaultPrices != null) defaultPrices.refreshForChecklist(id);
        return withUsage(mapper.toResponse(saved));
    }

    @Override
    @Transactional
    public ChecklistResponse updateStatus(String id, boolean active) {
        ChecklistDefinition entity = locked(id);
        boolean changed = !java.util.Objects.equals(entity.getIsActive(), active);
        entity.setIsActive(active);
        var saved = repository.saveAndFlush(entity);
        if (audit != null && changed) audit.record(active
                ? com.ondemandmonitoring.checklist.enums.ChecklistConfigAuditAction.CHECKLIST_ACTIVATED
                : com.ondemandmonitoring.checklist.enums.ChecklistConfigAuditAction.CHECKLIST_DEACTIVATED, saved, null);
        if (defaultPrices != null) defaultPrices.refreshForChecklist(id);
        return withUsage(mapper.toResponse(saved));
    }

    /**
     * Description and work guidance: an absent field keeps the stored value, an empty one clears it.
     * Blank list entries are dropped and the order is preserved.
     */
    private void applyGuidance(ChecklistDefinition entity, ChecklistRequest request) {
        if (request.getDescription() != null) {
            String text = request.getDescription().trim();
            entity.setDescription(text.isEmpty() ? null : text);
        }
        if (request.getExecutionSteps() != null) entity.setExecutionSteps(cleanList(request.getExecutionSteps()));
        if (request.getInputRequirements() != null) entity.setInputRequirements(cleanList(request.getInputRequirements()));
        if (request.getExpectedOutputs() != null) entity.setExpectedOutputs(cleanList(request.getExpectedOutputs()));
    }

    private static java.util.List<String> cleanList(java.util.List<String> values) {
        var cleaned = values.stream().filter(java.util.Objects::nonNull).map(String::trim)
                .filter(value -> !value.isEmpty()).toList();
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** Absent fields keep the stored value on update and take the neutral default on create. */
    private void applyPricing(ChecklistDefinition entity, ChecklistRequest request, boolean create) {
        if (request.getBasePrice() != null) {
            if (request.getBasePrice().signum() < 0 || request.getBasePrice().stripTrailingZeros().scale() > 0)
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "basePrice must be a non-negative whole amount");
            entity.setBasePrice(request.getBasePrice().setScale(0));
        } else if (create) entity.setBasePrice(java.math.BigDecimal.ZERO);
        if (request.getIncludedImageCount() != null) entity.setIncludedImageCount(request.getIncludedImageCount());
        if (request.getIncludedVideoCount() != null) entity.setIncludedVideoCount(request.getIncludedVideoCount());
        if (request.getStandardVideoDurationSeconds() != null) entity.setStandardVideoDurationSeconds(request.getStandardVideoDurationSeconds());
        if (request.getPricingProfile() != null) {
            String profile = request.getPricingProfile().trim();
            entity.setPricingProfile(profile.isEmpty() ? null : profile.toUpperCase(Locale.ROOT));
        }
        if (request.getAreaPricingEnabled() != null) entity.setAreaPricingEnabled(request.getAreaPricingEnabled());
        if (request.getLengthPricingEnabled() != null) entity.setLengthPricingEnabled(request.getLengthPricingEnabled());
        if (request.getAltitudePricingEnabled() != null) entity.setAltitudePricingEnabled(request.getAltitudePricingEnabled());
        if (request.getIncludedAreaHa() != null) entity.setIncludedAreaHa(request.getIncludedAreaHa());
        else if (create) entity.setIncludedAreaHa(java.math.BigDecimal.ZERO);
        if (request.getIncludedLengthKm() != null) entity.setIncludedLengthKm(request.getIncludedLengthKm());
        else if (create) entity.setIncludedLengthKm(java.math.BigDecimal.ZERO);
        if (request.getIncludedAltitudeM() != null) entity.setIncludedAltitudeM(request.getIncludedAltitudeM());
        else if (create) entity.setIncludedAltitudeM(java.math.BigDecimal.ZERO);
    }

    private ChecklistDefinition locked(String id) {
        return repository.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(ErrorCode.CHECKLIST_NOT_FOUND));
    }

}
