package com.ondemandmonitoring.checklist.service;

import com.ondemandmonitoring.checklist.domain.ChecklistConfigAudit;
import com.ondemandmonitoring.checklist.enums.ChecklistConfigAuditAction;
import com.ondemandmonitoring.checklist.domain.ChecklistDefinition;
import com.ondemandmonitoring.checklist.domain.ServiceChecklist;
import com.ondemandmonitoring.checklist.dto.response.ChecklistAuditResponse;
import com.ondemandmonitoring.checklist.repository.ChecklistConfigAuditRepository;
import com.ondemandmonitoring.common.api.PageResponse;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.service.domain.Service;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes and reads the administrator change history of the checklist library and service templates.
 * Entries are written inside the caller's transaction, so a change and its history commit or roll back together.
 */
@org.springframework.stereotype.Service
@RequiredArgsConstructor
@Slf4j
public class ChecklistAuditService {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<List<ChecklistAuditResponse.Change>> CHANGES = new TypeReference<>() {};
    private static final int MAX_VALUE = 1000;

    private final ChecklistConfigAuditRepository repository;
    private final AuthenticatedUserResolver currentUser;

    /** The tracked fields of a library checklist, as comparable text. */
    public Map<String, String> snapshot(ChecklistDefinition checklist) {
        var values = new LinkedHashMap<String, String>();
        values.put("content", checklist.getContent());
        values.put("description", checklist.getDescription());
        values.put("executionSteps", join(checklist.getExecutionSteps()));
        values.put("inputRequirements", join(checklist.getInputRequirements()));
        values.put("expectedOutputs", join(checklist.getExpectedOutputs()));
        values.put("difficulty", text(checklist.getDifficulty()));
        values.put("basePrice", text(checklist.getBasePrice()));
        values.put("includedImageCount", text(checklist.getIncludedImageCount()));
        values.put("includedVideoCount", text(checklist.getIncludedVideoCount()));
        values.put("standardVideoDurationSeconds", text(checklist.getStandardVideoDurationSeconds()));
        values.put("pricingProfile", checklist.getPricingProfile());
        values.put("areaPricingEnabled", text(checklist.isAreaPricingEnabled()));
        values.put("lengthPricingEnabled", text(checklist.isLengthPricingEnabled()));
        values.put("altitudePricingEnabled", text(checklist.isAltitudePricingEnabled()));
        values.put("includedAreaHa", text(checklist.getIncludedAreaHa()));
        values.put("includedLengthKm", text(checklist.getIncludedLengthKm()));
        values.put("includedAltitudeM", text(checklist.getIncludedAltitudeM()));
        values.put("isActive", text(checklist.getIsActive()));
        return values;
    }

    /** The tracked fields of a service's use of a checklist, as comparable text. */
    public Map<String, String> snapshot(ServiceChecklist link) {
        var values = new LinkedHashMap<String, String>();
        values.put("required", text(link.isRequired()));
        values.put("basePriceOverride", text(link.getBasePriceOverride()));
        values.put("includedImageCountOverride", text(link.getIncludedImageCountOverride()));
        values.put("includedVideoCountOverride", text(link.getIncludedVideoCountOverride()));
        values.put("pricingProfileOverride", link.getPricingProfileOverride());
        values.put("displayOrder", text(link.getDisplayOrder()));
        return values;
    }

    /** Records the fields that differ between {@code before} and {@code after}; nothing is written when none do. */
    public void record(ChecklistConfigAuditAction action, ChecklistDefinition checklist, Service service,
            Map<String, String> before, Map<String, String> after) {
        var changes = diff(before, after);
        boolean needsFields = action == ChecklistConfigAuditAction.CHECKLIST_UPDATED
                || action == ChecklistConfigAuditAction.SERVICE_CHECKLIST_UPDATED;
        if (needsFields && changes.isEmpty()) return;
        write(action, checklist, service, changes);
    }

    /** Records an event that has no field-level change, such as removing a checklist from a service. */
    public void record(ChecklistConfigAuditAction action, ChecklistDefinition checklist, Service service) {
        write(action, checklist, service, List.of());
    }

    @Transactional(readOnly = true)
    public PageResponse<ChecklistAuditResponse> list(String checklistId, String serviceId, int page, int size) {
        if (size < 1 || size > 100) throw new ApiException(ErrorCode.INVALID_REQUEST, "Page size must be between 1 and 100");
        if (page < 0) throw new ApiException(ErrorCode.INVALID_REQUEST, "Page must not be negative");
        Specification<ChecklistConfigAudit> filter = (root, query, cb) -> {
            var predicate = cb.conjunction();
            if (checklistId != null && !checklistId.isBlank())
                predicate = cb.and(predicate, cb.equal(root.get("checklistId"), checklistId));
            if (serviceId != null && !serviceId.isBlank())
                predicate = cb.and(predicate, cb.equal(root.get("serviceId"), serviceId));
            return predicate;
        };
        var sort = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));
        return PageResponse.from(repository.findAll(filter, PageRequest.of(page, size, sort)).map(this::toResponse));
    }

    static List<ChecklistAuditResponse.Change> diff(Map<String, String> before, Map<String, String> after) {
        var changes = new ArrayList<ChecklistAuditResponse.Change>();
        var fields = new LinkedHashMap<String, Boolean>();
        if (before != null) before.keySet().forEach(key -> fields.put(key, true));
        if (after != null) after.keySet().forEach(key -> fields.put(key, true));
        for (String field : fields.keySet()) {
            String from = normalize(before == null ? null : before.get(field));
            String to = normalize(after == null ? null : after.get(field));
            if (!Objects.equals(from, to)) changes.add(new ChecklistAuditResponse.Change(field, clip(from), clip(to)));
        }
        return changes;
    }

    private void write(ChecklistConfigAuditAction action, ChecklistDefinition checklist, Service service,
            List<ChecklistAuditResponse.Change> changes) {
        var entry = new ChecklistConfigAudit();
        entry.setAction(action);
        if (checklist != null) {
            entry.setChecklistId(checklist.getId());
            entry.setChecklistContent(clip(checklist.getContent(), 500));
        }
        if (service != null) {
            entry.setServiceId(service.getId());
            entry.setServiceName(clip(service.getName(), 255));
        }
        try {
            User actor = currentUser.getCurrentUser();
            entry.setActorId(actor.getId());
            entry.setActorName(clip(actor.getFullName(), 255));
        } catch (RuntimeException unavailable) {
            // Bootstrap and background jobs have no signed-in user; the entry is still worth keeping.
            entry.setActorName("system");
        }
        if (!changes.isEmpty()) entry.setChanges(MAPPER.writeValueAsString(changes));
        repository.save(entry);
    }

    private ChecklistAuditResponse toResponse(ChecklistConfigAudit entry) {
        List<ChecklistAuditResponse.Change> changes = List.of();
        if (entry.getChanges() != null && !entry.getChanges().isBlank()) {
            try {
                changes = MAPPER.readValue(entry.getChanges(), CHANGES);
            } catch (RuntimeException unreadable) {
                log.warn("Unreadable checklist audit changes for {}", entry.getId());
            }
        }
        return new ChecklistAuditResponse(entry.getId(), entry.getAction(), entry.getChecklistId(),
                entry.getChecklistContent(), entry.getServiceId(), entry.getServiceName(), entry.getActorId(),
                entry.getActorName(), changes, entry.getCreatedAt());
    }

    private static String join(List<String> values) {
        return values == null || values.isEmpty() ? null : String.join("\n", values);
    }

    private static String text(Object value) {
        if (value instanceof java.math.BigDecimal number) return number.stripTrailingZeros().toPlainString();
        return value == null ? null : String.valueOf(value);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String clip(String value) {
        return clip(value, MAX_VALUE);
    }

    private static String clip(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
