package com.ondemandmonitoring.devicecheck.service;

import com.ondemandmonitoring.devicecheck.domain.DeviceCheckCriterion;
import com.ondemandmonitoring.devicecheck.enums.DeviceCheckPhase;
import com.ondemandmonitoring.devicecheck.enums.PreDeviceCheckType;
import com.ondemandmonitoring.devicecheck.enums.PreflightCheckSource;
import com.ondemandmonitoring.devicecheck.repository.DeviceCheckCriterionRepository;
import com.ondemandmonitoring.devicecheck.service.impl.PersistedPreDeviceCheckService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Source of the criteria a new pre/post inspection run is built from. Criteria are admin-managed rows in
 * {@code device_check_criteria}; until a phase has any stored row, the built-in catalogue is used (and seeded on
 * the first admin access), so behaviour is unchanged on first deploy.
 */
@Service
@RequiredArgsConstructor
public class DeviceCheckCatalog {

    public static final String DEFAULT_CATEGORY = "KHÁC";

    /** Built-in codes whose evaluation has dedicated backend logic (battery threshold, connection fallback, ...). */
    private static final Set<String> SYSTEM_LOGIC_CODES = Set.of(
            PreDeviceCheckType.BATTERY_HEALTH.code(),
            PreDeviceCheckType.AIRCRAFT_CONNECTION.code(), PreDeviceCheckType.COMMAND_LINK.code(),
            PreDeviceCheckType.FLIGHT_CONTROLLER.code(), PreDeviceCheckType.AIRCRAFT_READINESS.code(),
            PreDeviceCheckType.MONITORING_CONNECTION.code(), PreDeviceCheckType.MEDIA_STORAGE.code(),
            PreDeviceCheckType.AIRSPACE_PERMISSION.code(), PreDeviceCheckType.CAMERA_HEALTH.code());

    /** A criterion as used by a run. {@code builtIn} is set for pre-flight codes backed by {@link PreDeviceCheckType}. */
    public record Criterion(String code, String name, String description, String category, boolean required,
                            PreflightCheckSource source, Optional<PreDeviceCheckType> builtIn) {
    }

    private record PostDefault(String code, String name, String category, boolean required) {
    }

    private static final List<PostDefault> POST_DEFAULTS = List.of(
            new PostDefault("a1", "Airframe condition", "THÂN MÁY", true),
            new PostDefault("a2", "Frame and landing gear", "THÂN MÁY", true),
            new PostDefault("p1", "Motors", "ĐỘNG LỰC", true),
            new PostDefault("p2", "Propellers", "ĐỘNG LỰC", true),
            new PostDefault("e1", "Landing battery", "ĐIỆN VÀ THIẾT BỊ", true),
            new PostDefault("e2", "Camera and payload", "ĐIỆN VÀ THIẾT BỊ", false),
            new PostDefault("e3", "GPS positioning", "ĐIỆN VÀ THIẾT BỊ", false),
            new PostDefault("e4", "Battery handling safety", "ĐIỆN VÀ THIẾT BỊ", true),
            new PostDefault("d1", "Communication logs", "DỮ LIỆU", false));

    private final DeviceCheckCriterionRepository repository;

    /** Active criteria for a new run, in admin order. */
    @Transactional(readOnly = true)
    public List<Criterion> activeCriteria(DeviceCheckPhase phase) {
        if (!repository.existsByPhase(phase)) return defaults(phase);
        return repository.findByPhaseOrderBySortOrderAscCreatedAtAsc(phase).stream()
                .filter(DeviceCheckCriterion::isActive)
                .map(DeviceCheckCatalog::toCriterion)
                .toList();
    }

    /** Stores the built-in catalogue for a phase that has no rows yet. Idempotent. */
    @Transactional
    public void seedIfEmpty(DeviceCheckPhase phase) {
        if (repository.existsByPhase(phase)) return;
        List<DeviceCheckCriterion> rows = new ArrayList<>();
        int order = 1;
        for (Criterion c : defaults(phase)) {
            DeviceCheckCriterion row = new DeviceCheckCriterion();
            row.setPhase(phase);
            row.setCode(c.code());
            row.setName(c.name());
            row.setDescription(c.description());
            row.setCategory(c.category());
            row.setRequired(c.required());
            row.setActive(true);
            row.setSortOrder(order++);
            rows.add(row);
        }
        repository.saveAll(rows);
    }

    public static boolean hasSystemLogic(DeviceCheckPhase phase, String code) {
        if (phase != DeviceCheckPhase.PRE) return false;
        return SYSTEM_LOGIC_CODES.contains(code)
                || PreDeviceCheckType.fromCode(code).map(t -> t.source() != PreflightCheckSource.MANUAL).orElse(false);
    }

    public static List<Criterion> defaults(DeviceCheckPhase phase) {
        if (phase == DeviceCheckPhase.PRE) {
            return PersistedPreDeviceCheckService.DEFAULT_CHECKS.stream()
                    .map(t -> new Criterion(t.code(), t.displayName(), t.description(), t.category(), t.required(),
                            t.source(), Optional.of(t)))
                    .toList();
        }
        return POST_DEFAULTS.stream()
                .map(d -> new Criterion(d.code(), d.name(), null, d.category(), d.required(),
                        PreflightCheckSource.MANUAL, Optional.empty()))
                .toList();
    }

    static Criterion toCriterion(DeviceCheckCriterion row) {
        Optional<PreDeviceCheckType> builtIn = row.getPhase() == DeviceCheckPhase.PRE
                ? PreDeviceCheckType.fromCode(row.getCode()) : Optional.empty();
        String description = row.getDescription() == null || row.getDescription().isBlank()
                ? row.getName() : row.getDescription();
        String category = row.getCategory() == null || row.getCategory().isBlank()
                ? DEFAULT_CATEGORY : row.getCategory();
        PreflightCheckSource source = builtIn.map(PreDeviceCheckType::source).orElse(PreflightCheckSource.MANUAL);
        return new Criterion(row.getCode(), row.getName(), description, category, row.isRequired(), source, builtIn);
    }
}
