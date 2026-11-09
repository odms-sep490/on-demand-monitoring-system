package com.ondemandmonitoring.pricing.service;

import com.ondemandmonitoring.checklist.repository.ServiceChecklistRepository;
import com.ondemandmonitoring.service.domain.Service;
import com.ondemandmonitoring.service.repository.ServiceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps {@code services.default_price} (the stored sum of a service's default checklists) in step with its source of truth.
 * The stored value is a cache for display; {@link ServicePriceResolver} stays the one place that knows how to compute it.
 * Call {@code refresh*} inside the transaction that changed a checklist, an association, or published a policy.
 */
@org.springframework.stereotype.Service
@RequiredArgsConstructor
public class ServiceDefaultPriceService {
    private final ServiceRepository services;
    private final ServiceChecklistRepository assignments;
    private final ServicePriceResolver resolver;

    @Transactional
    public void refresh(String serviceId) {
        services.findById(serviceId).ifPresent(this::store);
    }

    /** Every service that uses the checklist (its price, allowance, profile or active flag changed). */
    @Transactional
    public void refreshForChecklist(String checklistId) {
        for (var row : assignments.findAllByChecklistIdOrderByServiceNameAscServiceIdAsc(checklistId)) store(row.getService());
    }

    /** A published pricing policy can change every service (included-media prices, tiers, difficulty rules). */
    @Transactional
    public void refreshAll() {
        services.findAll().forEach(this::store);
    }

    /** Services never computed yet (for example created before this column existed). */
    @Transactional
    public int backfillMissing() {
        List<Service> missing = services.findAll().stream().filter(s -> s.getDefaultPrice() == null).toList();
        missing.forEach(this::store);
        return missing.size();
    }

    /** Services whose stored default price is missing or still zero (computed before their checklists were priced). */
    @Transactional
    public int refreshUnpriced() {
        List<Service> stale = services.findAll().stream()
                .filter(s -> s.getDefaultPrice() == null || s.getDefaultPrice().signum() == 0).toList();
        stale.forEach(this::store);
        return stale.size();
    }

    private void store(Service service) {
        BigDecimal checklistSum = resolver.resolveOrLegacy(service).checklistSubtotal();
        service.setDefaultPrice(checklistSum);
        service.setDefaultPriceComputedAt(Instant.now());
        services.save(service);
    }
}
