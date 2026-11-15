package com.ondemandmonitoring.pricing.service;

import com.ondemandmonitoring.checklist.domain.ChecklistDefinition;
import com.ondemandmonitoring.checklist.enums.ChecklistConfigAuditAction;
import com.ondemandmonitoring.checklist.repository.ChecklistDefinitionRepository;
import com.ondemandmonitoring.checklist.repository.ServiceChecklistRepository;
import com.ondemandmonitoring.checklist.service.ChecklistAuditService;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.pricing.dto.BaseChecklistPriceConfig;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BaseChecklistPricingService {
    private final ChecklistDefinitionRepository definitions;
    private final ChecklistAuditService audit;
    private final BaseChecklistPriceCalculator calculator;
    private final ServiceChecklistRepository serviceChecklists;
    private final com.ondemandmonitoring.pricing.service.ServiceDefaultPriceService defaultPrices;

    public BaseChecklistPricingService(ChecklistDefinitionRepository definitions, ChecklistAuditService audit,
            BaseChecklistPriceCalculator calculator, ServiceChecklistRepository serviceChecklists,
            com.ondemandmonitoring.pricing.service.ServiceDefaultPriceService defaultPrices) {
        this.definitions = definitions;
        this.audit = audit;
        this.calculator = calculator;
        this.serviceChecklists = serviceChecklists;
        this.defaultPrices = defaultPrices;
    }

    @Transactional(readOnly = true)
    public List<BaseChecklistPriceConfig.Row> list() {
        List<ChecklistDefinition> all = definitions.findAll(Sort.by(Sort.Direction.ASC, "content"));
        Map<String, List<String>> packageNames = packageNamesFor(all.stream().map(ChecklistDefinition::getId).toList());
        return all.stream().map(definition -> row(definition, packageNames.getOrDefault(definition.getId(), List.of()))).toList();
    }

    @Transactional
    public BaseChecklistPriceConfig.Row update(String id, BaseChecklistPriceConfig.Update request) {
        ChecklistDefinition definition = definitions.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(ErrorCode.CHECKLIST_NOT_FOUND));
        if (definition.getVersion() == null || !definition.getVersion().equals(request.expectedVersion()))
            throw new ApiException(ErrorCode.STALE_PRICING_REVISION, "Checklist price settings changed; reload and try again");
        var before = audit.snapshot(definition);
        definition.setBasePrice(request.basePrice().setScale(0));
        definition.setStandardAreaM2(request.standardAreaM2());
        if (request.extraImageUnitPrice() != null) definition.setExtraImageUnitPrice(request.extraImageUnitPrice().setScale(0));
        if (request.extraVideoUnitPrice() != null) definition.setExtraVideoUnitPrice(request.extraVideoUnitPrice().setScale(0));
        ChecklistDefinition saved = definitions.saveAndFlush(definition);
        audit.record(ChecklistConfigAuditAction.CHECKLIST_UPDATED, saved, null, before, audit.snapshot(saved));
        defaultPrices.refreshForChecklist(id);
        return row(saved, packageNamesFor(List.of(id)).getOrDefault(id, List.of()));
    }

    @Transactional(readOnly = true)
    public List<BaseChecklistPriceConfig.Row> preview(BigDecimal actualAreaM2) {
        List<ChecklistDefinition> all = definitions.findAll(Sort.by(Sort.Direction.ASC, "content"));
        Map<String, List<String>> packageNames = packageNamesFor(all.stream().map(ChecklistDefinition::getId).toList());
        return all.stream().map(definition -> previewRow(definition, actualAreaM2,
                packageNames.getOrDefault(definition.getId(), List.of()))).toList();
    }

    private BaseChecklistPriceConfig.Row previewRow(ChecklistDefinition definition, BigDecimal actualAreaM2,
            List<String> packageNames) {
        if (definition.getStandardAreaM2() == null || definition.getStandardAreaM2().signum() <= 0)
            return new BaseChecklistPriceConfig.Row(definition.getId(), definition.getContent(), definition.getBasePrice(),
                    definition.getStandardAreaM2(), definition.getExtraImageUnitPrice(), definition.getExtraVideoUnitPrice(),
                    Boolean.TRUE.equals(definition.getIsActive()), version(definition),
                    null, null, "Set a standard area greater than zero", packageNames);
        try {
            var calculation = calculator.calculate(definition.getBasePrice(), definition.getStandardAreaM2(), actualAreaM2);
            return new BaseChecklistPriceConfig.Row(definition.getId(), definition.getContent(), definition.getBasePrice(),
                    definition.getStandardAreaM2(), definition.getExtraImageUnitPrice(), definition.getExtraVideoUnitPrice(),
                    Boolean.TRUE.equals(definition.getIsActive()), version(definition),
                    calculation.calculatedPrice(), calculation.display(), null, packageNames);
        } catch (ApiException invalid) {
            return new BaseChecklistPriceConfig.Row(definition.getId(), definition.getContent(), definition.getBasePrice(),
                    definition.getStandardAreaM2(), definition.getExtraImageUnitPrice(), definition.getExtraVideoUnitPrice(),
                    Boolean.TRUE.equals(definition.getIsActive()), version(definition), null,
                    null, invalid.getMessage(), packageNames);
        }
    }

    private BaseChecklistPriceConfig.Row row(ChecklistDefinition definition, List<String> packageNames) {
        return new BaseChecklistPriceConfig.Row(definition.getId(), definition.getContent(), definition.getBasePrice(),
                definition.getStandardAreaM2(), definition.getExtraImageUnitPrice(), definition.getExtraVideoUnitPrice(),
                Boolean.TRUE.equals(definition.getIsActive()), version(definition),
                null, null, null, packageNames);
    }

    private Map<String, List<String>> packageNamesFor(List<String> checklistIds) {
        if (checklistIds.isEmpty()) return Map.of();
        return serviceChecklists.findPackageNamesByChecklistIds(checklistIds).stream().collect(
                Collectors.groupingBy(ServiceChecklistRepository.PackageUsage::getChecklistId,
                        Collectors.mapping(ServiceChecklistRepository.PackageUsage::getPackageName, Collectors.toList())));
    }

    private long version(ChecklistDefinition definition) {
        return definition.getVersion() == null ? 0 : definition.getVersion();
    }
}
