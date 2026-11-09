package com.ondemandmonitoring.checklist.service.impl;

import com.ondemandmonitoring.checklist.domain.*;
import com.ondemandmonitoring.checklist.dto.request.ChecklistAssignmentRequest;
import com.ondemandmonitoring.checklist.dto.response.ServiceChecklistResponse;
import com.ondemandmonitoring.checklist.mapper.ServiceChecklistMapper;
import com.ondemandmonitoring.checklist.repository.*;
import com.ondemandmonitoring.checklist.service.IServiceChecklistService;
import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.service.domain.Service;
import com.ondemandmonitoring.service.repository.ServiceRepository;
import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.ondemandmonitoring.checklist.dto.request.ChecklistReorderRequest;
import org.springframework.transaction.annotation.Transactional;

@org.springframework.stereotype.Service
@RequiredArgsConstructor
@Transactional
public class ServiceChecklistServiceImpl implements IServiceChecklistService {
    private final ServiceRepository services;
    private final ChecklistDefinitionRepository checklists;
    private final ServiceChecklistRepository assignments;
    private final ServiceChecklistMapper mapper;

    private com.ondemandmonitoring.pricing.service.ServiceDefaultPriceService defaultPrices;

    private com.ondemandmonitoring.checklist.service.ChecklistAuditService audit;

    /** Optional collaborator: the change history. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAudit(com.ondemandmonitoring.checklist.service.ChecklistAuditService audit) {
        this.audit = audit;
    }

    /** Optional collaborator: keeps the stored service default price current. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDefaultPrices(com.ondemandmonitoring.pricing.service.ServiceDefaultPriceService defaultPrices) {
        this.defaultPrices = defaultPrices;
    }

    @Override
    public ServiceChecklistResponse assign(String serviceId, ChecklistAssignmentRequest request) {
        validateOrder(request.getDisplayOrder());
        Service service = lockService(serviceId);
        ChecklistDefinition checklist = checklists.findByIdForUpdate(request.getChecklistId())
                .orElseThrow(() -> new ApiException(ErrorCode.CHECKLIST_NOT_FOUND));
        if (!Boolean.TRUE.equals(service.getIsActive())) throw new ApiException(ErrorCode.SERVICE_INACTIVE);
        if (!Boolean.TRUE.equals(checklist.getIsActive())) throw new ApiException(ErrorCode.CHECKLIST_INACTIVE);
        if (assignments.existsByServiceIdAndChecklistId(serviceId, checklist.getId())) {
            throw new ApiException(ErrorCode.SERVICE_CHECKLIST_ALREADY_EXISTS);
        }
        ServiceChecklist entity = new ServiceChecklist();
        entity.setService(service);
        entity.setChecklist(checklist);
        entity.setRequired(Boolean.TRUE.equals(request.getRequired()));
        applyOverrides(entity, request.getBasePriceOverride(), request.getIncludedImageCountOverride(),
                request.getIncludedVideoCountOverride(), request.getPricingProfileOverride());
        var rows = new ArrayList<>(assignments.findAllByServiceIdOrderByDisplayOrderAscIdAsc(serviceId));
        if (rows.size() >= 100) throw new ApiException(ErrorCode.INVALID_REQUEST, "At most 100 template items are allowed");
        rows.add(Math.min(request.getDisplayOrder(), rows.size()), entity);
        normalize(rows);
        service.setChecklistDefaultsInitialized(true);
        var saved = assignments.saveAndFlush(entity);
        if (audit != null) audit.record(com.ondemandmonitoring.checklist.enums.ChecklistConfigAuditAction.SERVICE_CHECKLIST_ASSIGNED, checklist, service,
                java.util.Map.of(), audit.snapshot(saved));
        refreshPrice(serviceId);
        return mapper.toResponse(saved);
    }

    @Override
    public ServiceChecklistResponse updateOrder(String serviceId, String checklistId, int displayOrder) {
        validateOrder(displayOrder);
        Service service = lockService(serviceId);
        ServiceChecklist entity = assignment(serviceId, checklistId);
        var rows = new ArrayList<>(assignments.findAllByServiceIdOrderByDisplayOrderAscIdAsc(serviceId));
        rows.removeIf(row -> checklistId.equals(row.getChecklist().getId()));
        rows.add(Math.min(displayOrder, rows.size()), entity);
        normalize(rows);
        service.setChecklistDefaultsInitialized(true);
        return mapper.toResponse(assignments.saveAndFlush(entity));
    }

    @Override
    public void unassign(String serviceId, String checklistId) {
        Service service = lockService(serviceId);
        var removed = assignment(serviceId, checklistId);
        var removedChecklist = removed.getChecklist();
        assignments.delete(removed);
        assignments.flush();
        if (audit != null) audit.record(com.ondemandmonitoring.checklist.enums.ChecklistConfigAuditAction.SERVICE_CHECKLIST_REMOVED, removedChecklist, service);
        normalize(assignments.findAllByServiceIdOrderByDisplayOrderAscIdAsc(serviceId));
        service.setChecklistDefaultsInitialized(true);
        refreshPrice(serviceId);
    }

    @Override
    public List<ServiceChecklistResponse> reorder(String serviceId, ChecklistReorderRequest request) {
        Service service = lockService(serviceId);
        var rows = assignments.findAllByServiceIdOrderByDisplayOrderAscIdAsc(serviceId);
        if (request == null || request.items() == null || request.items().size() != rows.size()
                || rows.size() > 100 || request.items().stream().anyMatch(item -> item == null || item.checklistId() == null))
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Reorder must include every assigned checklist once");
        var byId = rows.stream().collect(Collectors.toMap(row -> row.getChecklist().getId(), Function.identity()));
        var ordered = new ArrayList<ServiceChecklist>();
        for (var item : request.items()) {
            var row = byId.remove(item.checklistId());
            if (row == null) throw new ApiException(ErrorCode.INVALID_REQUEST, "Duplicate or unknown checklist");
            if (!Objects.equals(row.getVersion(), item.expectedVersion()) || item.expectedVersion() == null)
                throw new ApiException(ErrorCode.CONCURRENT_UPDATE);
            ordered.add(row);
        }
        var before = rows.stream().map(row -> row.getChecklist().getId()).toList();
        normalize(ordered);
        service.setChecklistDefaultsInitialized(true);
        if (audit != null && !before.equals(ordered.stream().map(row -> row.getChecklist().getId()).toList()))
            audit.record(com.ondemandmonitoring.checklist.enums.ChecklistConfigAuditAction.SERVICE_CHECKLIST_REORDERED, null, service);
        return ordered.stream().map(mapper::toResponse).toList();
    }

    @Override
    public ServiceChecklistResponse updatePricing(String serviceId, String checklistId,
            com.ondemandmonitoring.checklist.dto.request.ServiceChecklistPricingRequest request) {
        Service service = lockService(serviceId);
        ServiceChecklist entity = assignment(serviceId, checklistId);
        if (request.getExpectedVersion() != null && !Objects.equals(entity.getVersion(), request.getExpectedVersion()))
            throw new ApiException(ErrorCode.CONCURRENT_UPDATE);
        var before = audit == null ? null : audit.snapshot(entity);
        applyOverrides(entity, request.getBasePriceOverride(), request.getIncludedImageCountOverride(),
                request.getIncludedVideoCountOverride(), request.getPricingProfileOverride());
        if (request.getRequired() != null) entity.setRequired(request.getRequired());
        service.setChecklistDefaultsInitialized(true);
        var saved = assignments.saveAndFlush(entity);
        if (audit != null) audit.record(com.ondemandmonitoring.checklist.enums.ChecklistConfigAuditAction.SERVICE_CHECKLIST_UPDATED, saved.getChecklist(), service,
                before, audit.snapshot(saved));
        refreshPrice(serviceId);
        return mapper.toResponse(saved);
    }

    /**
     * Gives this service its own copy of a shared checklist and points the service at it, so the copy can be edited
     * without touching the other services that use the original. Order, "required" and the price overrides move with it;
     * orders already placed keep their frozen snapshot.
     */
    @Override
    public ServiceChecklistResponse fork(String serviceId, String checklistId) {
        Service service = lockService(serviceId);
        ServiceChecklist link = assignment(serviceId, checklistId);
        ChecklistDefinition source = checklists.findByIdForUpdate(checklistId)
                .orElseThrow(() -> new ApiException(ErrorCode.CHECKLIST_NOT_FOUND));
        ChecklistDefinition copy = new ChecklistDefinition();
        String content = uniqueCopyContent(source.getContent(), service.getName());
        copy.setContent(content);
        copy.setNormalizedContent(content.toLowerCase(java.util.Locale.ROOT));
        copy.setIsActive(true);
        copy.setDescription(source.getDescription());
        copy.setExecutionSteps(source.getExecutionSteps() == null ? null : List.copyOf(source.getExecutionSteps()));
        copy.setInputRequirements(source.getInputRequirements() == null ? null : List.copyOf(source.getInputRequirements()));
        copy.setExpectedOutputs(source.getExpectedOutputs() == null ? null : List.copyOf(source.getExpectedOutputs()));
        copy.setDifficulty(source.getDifficulty());
        copy.setBasePrice(source.getBasePrice());
        copy.setIncludedImageCount(source.getIncludedImageCount());
        copy.setIncludedVideoCount(source.getIncludedVideoCount());
        copy.setStandardVideoDurationSeconds(source.getStandardVideoDurationSeconds());
        copy.setPricingProfile(source.getPricingProfile());
        copy.setAreaPricingEnabled(source.isAreaPricingEnabled());
        copy.setLengthPricingEnabled(source.isLengthPricingEnabled());
        copy.setAltitudePricingEnabled(source.isAltitudePricingEnabled());
        copy.setIncludedAreaHa(source.getIncludedAreaHa());
        copy.setIncludedLengthKm(source.getIncludedLengthKm());
        copy.setIncludedAltitudeM(source.getIncludedAltitudeM());
        ChecklistDefinition savedCopy = checklists.saveAndFlush(copy);
        link.setChecklist(savedCopy);
        service.setChecklistDefaultsInitialized(true);
        var saved = assignments.saveAndFlush(link);
        if (audit != null) audit.record(com.ondemandmonitoring.checklist.enums.ChecklistConfigAuditAction.SERVICE_CHECKLIST_FORKED, savedCopy, service,
                java.util.Map.of("checklist", source.getContent()), java.util.Map.of("checklist", savedCopy.getContent()));
        refreshPrice(serviceId);
        return mapper.toResponse(saved);
    }

    /** "<content> (<service>)", numbered when that name is taken; always within the 500-character limit. */
    private String uniqueCopyContent(String content, String serviceName) {
        String suffix = " (" + (serviceName == null ? "riêng" : serviceName.trim()) + ")";
        if (suffix.length() > 120) suffix = suffix.substring(0, 119) + ")";
        for (int attempt = 1; attempt <= 50; attempt++) {
            String tail = attempt == 1 ? suffix : suffix + " #" + attempt;
            String head = content.length() + tail.length() > 500 ? content.substring(0, 500 - tail.length()) : content;
            String candidate = com.ondemandmonitoring.checklist.util.ChecklistContentNormalizer.content(head + tail);
            if (!checklists.existsByNormalizedContent(candidate.toLowerCase(java.util.Locale.ROOT))) return candidate;
        }
        throw new ApiException(ErrorCode.CHECKLIST_ALREADY_EXISTS);
    }

    private void refreshPrice(String serviceId) {
        if (defaultPrices != null) defaultPrices.refresh(serviceId);
    }

    /** Overrides live on the association only: another Service using the same checklist is never affected. */
    private void applyOverrides(ServiceChecklist entity, java.math.BigDecimal base, Integer images, Integer videos, String profile) {
        if (base != null && (base.signum() < 0 || base.stripTrailingZeros().scale() > 0))
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "basePriceOverride must be a non-negative whole amount");
        entity.setBasePriceOverride(base == null ? null : base.setScale(0));
        entity.setIncludedImageCountOverride(images);
        entity.setIncludedVideoCountOverride(videos);
        entity.setPricingProfileOverride(profile == null || profile.isBlank() ? null : profile.trim().toUpperCase(java.util.Locale.ROOT));
    }

    private void normalize(List<ServiceChecklist> rows) {
        for (int i = 0; i < rows.size(); i++) rows.get(i).setDisplayOrder(i);
        assignments.saveAllAndFlush(rows);
    }

    private Service lockService(String id) {
        return services.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(ErrorCode.SERVICE_NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ServiceChecklistResponse> getByService(String serviceId, boolean activeOnly) {
        Service service = services.findById(serviceId)
                .orElseThrow(() -> new ApiException(ErrorCode.SERVICE_NOT_FOUND));
        if (activeOnly && !Boolean.TRUE.equals(service.getIsActive())) {
            throw new ApiException(ErrorCode.SERVICE_INACTIVE);
        }
        List<ServiceChecklist> rows = activeOnly
                ? assignments.findAllByServiceIdAndChecklistIsActiveTrueOrderByDisplayOrderAscIdAsc(serviceId)
                : assignments.findAllByServiceIdOrderByDisplayOrderAscIdAsc(serviceId);
        var responses = rows.stream().map(mapper::toResponse).toList();
        if (!activeOnly && !responses.isEmpty()) {
            // The admin template shows which checklists are shared; customers do not need the figure.
            var counts = new java.util.HashMap<String, Long>();
            assignments.countByChecklistIds(responses.stream().map(ServiceChecklistResponse::getChecklistId).toList())
                    .forEach(row -> counts.put(row.getChecklistId(), row.getTotal()));
            responses.forEach(response -> response.setUsedByServiceCount(counts.getOrDefault(response.getChecklistId(), 0L)));
        }
        return responses;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ServiceChecklistResponse> getByChecklist(String checklistId) {
        if (!checklists.existsById(checklistId)) throw new ApiException(ErrorCode.CHECKLIST_NOT_FOUND);
        return assignments.findAllByChecklistIdOrderByServiceNameAscServiceIdAsc(checklistId)
                .stream().map(mapper::toResponse).toList();
    }

    private ServiceChecklist assignment(String serviceId, String checklistId) {
        return assignments.findByServiceIdAndChecklistId(serviceId, checklistId)
                .orElseThrow(() -> new ApiException(ErrorCode.SERVICE_CHECKLIST_NOT_FOUND));
    }

    private void validateOrder(Integer order) {
        if (order == null || order < 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Display order must be non-negative");
        }
    }
}
