package com.ondemandmonitoring.devicecheck.service.impl;

import com.ondemandmonitoring.audit.enums.AuditAction;
import com.ondemandmonitoring.audit.service.AdminAuditLogService;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.devicecheck.domain.DeviceCheckCriterion;
import com.ondemandmonitoring.devicecheck.dto.request.DeviceCheckCriterionReorderRequest;
import com.ondemandmonitoring.devicecheck.dto.request.DeviceCheckCriterionRequest;
import com.ondemandmonitoring.devicecheck.dto.response.DeviceCheckCriterionResponse;
import com.ondemandmonitoring.devicecheck.enums.DeviceCheckPhase;
import com.ondemandmonitoring.devicecheck.repository.DeviceCheckCriterionRepository;
import com.ondemandmonitoring.devicecheck.service.DeviceCheckCatalog;
import com.ondemandmonitoring.devicecheck.service.IDeviceCheckCriterionAdminService;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeviceCheckCriterionAdminService implements IDeviceCheckCriterionAdminService {

    static final String AUDIT_ENTITY = "DEVICE_CHECK_CRITERION";

    private final DeviceCheckCriterionRepository repository;
    private final DeviceCheckCatalog catalog;
    private final AdminAuditLogService audit;

    @Override
    @Transactional
    public List<DeviceCheckCriterionResponse> list(DeviceCheckPhase phase) {
        catalog.seedIfEmpty(phase);
        return repository.findByPhaseOrderBySortOrderAscCreatedAtAsc(phase).stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public DeviceCheckCriterionResponse get(String id) {
        return toResponse(find(id));
    }

    @Override
    @Transactional
    public DeviceCheckCriterionResponse create(DeviceCheckCriterionRequest request) {
        if (request.phase() == null) throw new ApiException(ErrorCode.VALIDATION_ERROR, "phase là bắt buộc");
        DeviceCheckPhase phase = request.phase();
        catalog.seedIfEmpty(phase);
        String code = request.code() == null || request.code().isBlank() ? uniqueSlug(phase, request.name()) : request.code();
        if (repository.existsByPhaseAndCode(phase, code)) {
            throw new ApiException(ErrorCode.RESOURCE_ALREADY_EXISTS, "Mã tiêu chí đã tồn tại: " + code);
        }
        DeviceCheckCriterion row = new DeviceCheckCriterion();
        row.setPhase(phase);
        row.setCode(code);
        row.setSortOrder(repository.nextSortOrder(phase));
        row.setRequired(Boolean.TRUE.equals(request.required()));
        row.setActive(request.active() == null || request.active());
        applyText(row, request);
        DeviceCheckCriterion saved = repository.saveAndFlush(row);
        audit.record(AuditAction.CREATE, AUDIT_ENTITY, saved.getId(), null, snapshot(saved));
        return toResponse(saved);
    }

    @Override
    @Transactional
    public DeviceCheckCriterionResponse update(String id, DeviceCheckCriterionRequest request) {
        DeviceCheckCriterion row = find(id);
        if (request.version() != null && !Objects.equals(request.version(), row.getVersion())) {
            throw new ApiException(ErrorCode.CONCURRENT_UPDATE);
        }
        Map<String, Object> before = snapshot(row);
        boolean active = request.active() == null ? row.isActive() : request.active();
        if (row.isActive() && !active) ensureNotLastActive(row);
        row.setActive(active);
        if (request.required() != null) row.setRequired(request.required());
        applyText(row, request);
        DeviceCheckCriterion saved = repository.saveAndFlush(row);
        audit.record(AuditAction.UPDATE, AUDIT_ENTITY, saved.getId(), before, snapshot(saved));
        return toResponse(saved);
    }

    @Override
    @Transactional
    public void delete(String id) {
        DeviceCheckCriterion row = find(id);
        if (row.isActive()) ensureNotLastActive(row);
        Map<String, Object> before = snapshot(row);
        repository.delete(row);
        audit.record(AuditAction.DELETE, AUDIT_ENTITY, id, before, null);
    }

    @Override
    @Transactional
    public List<DeviceCheckCriterionResponse> reorder(DeviceCheckCriterionReorderRequest request) {
        List<DeviceCheckCriterion> rows = repository.findByPhaseOrderBySortOrderAscCreatedAtAsc(request.phase());
        var known = rows.stream().map(DeviceCheckCriterion::getId).toList();
        if (request.ids().size() != known.size() || !new HashSet<>(request.ids()).equals(new HashSet<>(known))) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Danh sách sắp xếp phải gồm đúng toàn bộ tiêu chí của loại kiểm tra");
        }
        Map<String, Object> before = Map.of("order", known);
        Map<String, DeviceCheckCriterion> byId = new LinkedHashMap<>();
        rows.forEach(r -> byId.put(r.getId(), r));
        for (int i = 0; i < request.ids().size(); i++) byId.get(request.ids().get(i)).setSortOrder(i + 1);
        repository.saveAll(rows);
        audit.record(AuditAction.UPDATE, AUDIT_ENTITY, request.phase().name(), before, Map.of("order", request.ids()));
        return repository.findByPhaseOrderBySortOrderAscCreatedAtAsc(request.phase()).stream().map(this::toResponse).toList();
    }

    private void ensureNotLastActive(DeviceCheckCriterion row) {
        if (repository.countByPhaseAndActiveTrue(row.getPhase()) <= 1) {
            throw new ApiException(ErrorCode.DEVICE_CHECK_LAST_ACTIVE_CRITERION);
        }
    }

    private DeviceCheckCriterion find(String id) {
        return repository.findById(id).orElseThrow(() -> new ApiException(ErrorCode.DEVICE_CHECK_CRITERION_NOT_FOUND));
    }

    private static void applyText(DeviceCheckCriterion row, DeviceCheckCriterionRequest request) {
        row.setName(request.name().trim());
        row.setDescription(blankToNull(request.description()));
        String category = blankToNull(request.category());
        row.setCategory(category == null ? null : category.toUpperCase(Locale.forLanguageTag("vi")));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String uniqueSlug(DeviceCheckPhase phase, String name) {
        String base = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace('đ', 'd').replace('Đ', 'D')
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (base.isEmpty()) base = "criterion";
        if (base.length() > 44) base = base.substring(0, 44);
        String code = base;
        for (int n = 2; repository.existsByPhaseAndCode(phase, code); n++) code = base + "_" + n;
        return code;
    }

    private static Map<String, Object> snapshot(DeviceCheckCriterion row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("phase", row.getPhase().name());
        map.put("code", row.getCode());
        map.put("name", row.getName());
        map.put("description", row.getDescription());
        map.put("category", row.getCategory());
        map.put("required", row.isRequired());
        map.put("active", row.isActive());
        map.put("sortOrder", row.getSortOrder());
        return map;
    }

    private DeviceCheckCriterionResponse toResponse(DeviceCheckCriterion row) {
        return DeviceCheckCriterionResponse.builder()
                .id(row.getId())
                .phase(row.getPhase())
                .code(row.getCode())
                .name(row.getName())
                .description(row.getDescription())
                .category(row.getCategory() == null ? DeviceCheckCatalog.DEFAULT_CATEGORY : row.getCategory())
                .required(row.isRequired())
                .active(row.isActive())
                .sortOrder(row.getSortOrder())
                .systemLogic(DeviceCheckCatalog.hasSystemLogic(row.getPhase(), row.getCode()))
                .createdAt(row.getCreatedAt())
                .updatedAt(row.getUpdatedAt())
                .version(row.getVersion())
                .build();
    }
}
