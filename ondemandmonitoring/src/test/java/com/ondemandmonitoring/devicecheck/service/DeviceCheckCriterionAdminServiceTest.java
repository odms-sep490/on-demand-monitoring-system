package com.ondemandmonitoring.devicecheck.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.audit.enums.AuditAction;
import com.ondemandmonitoring.audit.service.AdminAuditLogService;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.devicecheck.domain.DeviceCheckCriterion;
import com.ondemandmonitoring.devicecheck.dto.request.DeviceCheckCriterionReorderRequest;
import com.ondemandmonitoring.devicecheck.dto.request.DeviceCheckCriterionRequest;
import com.ondemandmonitoring.devicecheck.enums.DeviceCheckPhase;
import com.ondemandmonitoring.devicecheck.repository.DeviceCheckCriterionRepository;
import com.ondemandmonitoring.devicecheck.service.impl.DeviceCheckCriterionAdminService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeviceCheckCriterionAdminServiceTest {
    DeviceCheckCriterionRepository repo = mock(DeviceCheckCriterionRepository.class);
    AdminAuditLogService audit = mock(AdminAuditLogService.class);
    DeviceCheckCatalog catalog = new DeviceCheckCatalog(repo);
    DeviceCheckCriterionAdminService service = new DeviceCheckCriterionAdminService(repo, catalog, audit);
    List<DeviceCheckCriterion> store = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(repo.existsByPhase(any())).thenAnswer(i -> store.stream().anyMatch(r -> r.getPhase() == i.getArgument(0)));
        when(repo.existsByPhaseAndCode(any(), any())).thenAnswer(i -> store.stream()
                .anyMatch(r -> r.getPhase() == i.getArgument(0) && r.getCode().equals(i.getArgument(1))));
        when(repo.findByPhaseOrderBySortOrderAscCreatedAtAsc(any())).thenAnswer(i -> store.stream()
                .filter(r -> r.getPhase() == i.getArgument(0))
                .sorted(Comparator.comparingInt(DeviceCheckCriterion::getSortOrder)).toList());
        when(repo.nextSortOrder(any())).thenCallRealMethod();
        when(repo.countByPhaseAndActiveTrue(any())).thenAnswer(i -> store.stream()
                .filter(r -> r.getPhase() == i.getArgument(0) && r.isActive()).count());
        when(repo.findById(any())).thenAnswer(i -> store.stream().filter(r -> r.getId().equals(i.getArgument(0))).findFirst());
        when(repo.save(any())).thenAnswer(i -> persist(i.getArgument(0)));
        when(repo.saveAndFlush(any())).thenAnswer(i -> persist(i.getArgument(0)));
        when(repo.saveAll(any())).thenAnswer(i -> {
            Iterable<DeviceCheckCriterion> rows = i.getArgument(0);
            rows.forEach(this::persist);
            return rows;
        });
        org.mockito.Mockito.doAnswer(i -> store.remove(i.<DeviceCheckCriterion>getArgument(0))).when(repo).delete(any());
    }

    DeviceCheckCriterion persist(DeviceCheckCriterion row) {
        if (row.getId() == null) {
            row.setId("c" + store.size() + "-" + row.getCode());
            row.setVersion(0L);
            store.add(row);
        }
        return row;
    }

    static DeviceCheckCriterionRequest req(DeviceCheckPhase phase, String code, String name, Boolean required, Boolean active) {
        return new DeviceCheckCriterionRequest(phase, code, name, "mô tả", "thiết bị bay", required, active, null);
    }

    @Test
    void firstListSeedsBuiltInCatalogueInOrder() {
        var pre = service.list(DeviceCheckPhase.PRE);
        var post = service.list(DeviceCheckPhase.POST);
        int preDefaults = DeviceCheckCatalog.defaults(DeviceCheckPhase.PRE).size();
        assertThat(pre).hasSize(preDefaults);
        assertThat(pre.get(0).code()).isEqualTo("battery_health");
        assertThat(pre.get(0).systemLogic()).isTrue();
        assertThat(pre).extracting(r -> r.sortOrder())
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, preDefaults).boxed().toList());
        assertThat(post).hasSize(9);
        assertThat(post).allSatisfy(r -> assertThat(r.systemLogic()).isFalse());
        service.list(DeviceCheckPhase.PRE);
        assertThat(store).hasSize(preDefaults + 9); // idempotent
    }

    @Test
    void createSlugsVietnameseNameAppendsAtEndAndAudits() {
        service.list(DeviceCheckPhase.POST);
        var created = service.create(req(DeviceCheckPhase.POST, null, "Kiểm tra đèn LED định vị", true, null));
        assertThat(created.code()).isEqualTo("kiem_tra_den_led_dinh_vi");
        assertThat(created.sortOrder()).isEqualTo(10);
        assertThat(created.category()).isEqualTo("THIẾT BỊ BAY");
        assertThat(created.active()).isTrue();
        verify(audit).record(eq(AuditAction.CREATE), eq("DEVICE_CHECK_CRITERION"), eq(created.id()), isNull(), any());
        var dup = service.create(req(DeviceCheckPhase.POST, null, "Kiểm tra đèn LED định vị", false, null));
        assertThat(dup.code()).isEqualTo("kiem_tra_den_led_dinh_vi_2");
    }

    @Test
    void duplicateExplicitCodeIsRejected() {
        service.list(DeviceCheckPhase.PRE);
        assertThatThrownBy(() -> service.create(req(DeviceCheckPhase.PRE, "battery_health", "Pin", true, true)))
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_ALREADY_EXISTS));
    }

    @Test
    void updateChangesFieldsButNotCodeAndRejectsStaleVersion() {
        var first = service.list(DeviceCheckPhase.POST).get(0);
        var updated = service.update(first.id(), new DeviceCheckCriterionRequest(
                DeviceCheckPhase.PRE, "hacked", "Tình trạng thân máy", null, "thân máy", false, true, 0L));
        assertThat(updated.code()).isEqualTo("a1");
        assertThat(updated.phase()).isEqualTo(DeviceCheckPhase.POST);
        assertThat(updated.name()).isEqualTo("Tình trạng thân máy");
        assertThat(updated.required()).isFalse();
        assertThatThrownBy(() -> service.update(first.id(), new DeviceCheckCriterionRequest(
                null, null, "x", null, null, null, null, 99L)))
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.CONCURRENT_UPDATE));
    }

    @Test
    void cannotDisableOrDeleteTheLastActiveCriterionOfAPhase() {
        var post = service.list(DeviceCheckPhase.POST);
        for (int i = 1; i < post.size(); i++) service.delete(post.get(i).id());
        String last = post.get(0).id();
        assertThatThrownBy(() -> service.delete(last))
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.DEVICE_CHECK_LAST_ACTIVE_CRITERION));
        assertThatThrownBy(() -> service.update(last, req(null, null, "x", true, false)))
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.DEVICE_CHECK_LAST_ACTIVE_CRITERION));
        assertThat(store.stream().filter(r -> r.getPhase() == DeviceCheckPhase.POST)).hasSize(1);
    }

    @Test
    void builtInSystemCriterionCanBeDeleted() {
        var battery = service.list(DeviceCheckPhase.PRE).get(0);
        service.delete(battery.id());
        verify(audit).record(eq(AuditAction.DELETE), any(), eq(battery.id()), any(), isNull());
        assertThat(catalog.activeCriteria(DeviceCheckPhase.PRE)).extracting(DeviceCheckCatalog.Criterion::code)
                .doesNotContain("battery_health").hasSize(DeviceCheckCatalog.defaults(DeviceCheckPhase.PRE).size() - 1);
    }

    @Test
    void reorderRequiresFullSetAndPersistsOrder() {
        var post = service.list(DeviceCheckPhase.POST);
        var ids = new ArrayList<>(post.stream().map(r -> r.id()).toList());
        java.util.Collections.reverse(ids);
        var result = service.reorder(new DeviceCheckCriterionReorderRequest(DeviceCheckPhase.POST, ids));
        assertThat(result).extracting(r -> r.code()).first().isEqualTo("d1");
        assertThat(catalog.activeCriteria(DeviceCheckPhase.POST).get(0).code()).isEqualTo("d1");
        assertThatThrownBy(() -> service.reorder(new DeviceCheckCriterionReorderRequest(DeviceCheckPhase.POST, ids.subList(0, 3))))
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void catalogueUsesDefaultsUntilAnythingIsStoredAndSkipsInactive() {
        assertThat(catalog.activeCriteria(DeviceCheckPhase.PRE)).hasSize(DeviceCheckCatalog.defaults(DeviceCheckPhase.PRE).size());
        var created = service.create(req(DeviceCheckPhase.PRE, "custom_check", "Tiêu chí riêng", true, true));
        var battery = store.stream().filter(r -> r.getCode().equals("battery_health")).findFirst().orElseThrow();
        service.update(battery.getId(), req(null, null, "Pin", true, false));
        var active = catalog.activeCriteria(DeviceCheckPhase.PRE);
        assertThat(active).extracting(DeviceCheckCatalog.Criterion::code).doesNotContain("battery_health").contains("custom_check");
        var custom = active.stream().filter(c -> c.code().equals("custom_check")).findFirst().orElseThrow();
        assertThat(custom.builtIn()).isEmpty();
        assertThat(custom.source()).isEqualTo(com.ondemandmonitoring.devicecheck.enums.PreflightCheckSource.MANUAL);
        assertThat(created.systemLogic()).isFalse();
        verify(repo, never()).delete(any());
    }
}
