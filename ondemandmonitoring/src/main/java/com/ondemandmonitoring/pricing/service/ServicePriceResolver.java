package com.ondemandmonitoring.pricing.service;

import com.ondemandmonitoring.checklist.domain.ServiceChecklist;
import com.ondemandmonitoring.checklist.repository.ServiceChecklistRepository;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.pricing.domain.PricingPolicy;
import com.ondemandmonitoring.pricing.repository.PricingPolicyRepository;
import com.ondemandmonitoring.service.domain.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single rule for "what does a Service cost by default":
 * <pre>
 * default price = sum(price of each active default checklist at its included allowance)
 * </pre>
 * The checklists are the only price source: {@code services.base_price} is no longer a fallback. A service whose default
 * checklists add up to nothing is reported as {@link #UNPRICED} so the UI can say so and orders can be refused.
 */
@Component
@RequiredArgsConstructor
public class ServicePriceResolver {
    public static final String CHECKLISTS = "CHECKLISTS";
    public static final String UNPRICED = "UNPRICED";

    private final ServiceChecklistRepository assignments;
    private final PricingPolicyRepository policies;
    private final ChecklistPriceCalculator calculator;

    /** @param packageFee always 0: the checklists are the whole price. Kept for response compatibility. */
    public record Resolved(String source, BigDecimal packageFee, BigDecimal checklistSubtotal, BigDecimal total) {}

    /** Decision from the amount the default checklists add up to. */
    public static Resolved of(BigDecimal checklistSubtotal) {
        BigDecimal subtotal = whole(checklistSubtotal);
        return new Resolved(subtotal.signum() > 0 ? CHECKLISTS : UNPRICED, BigDecimal.ZERO, subtotal, subtotal);
    }

    /** Strict: pricing problems of a default checklist surface as {@link ApiException}. */
    @Transactional(readOnly = true)
    public Resolved resolve(Service service, PricingPolicy policy) {
        BigDecimal subtotal = BigDecimal.ZERO;
        List<ServiceChecklist> rows = assignments.findAllByServiceIdAndChecklistIsActiveTrueOrderByDisplayOrderAscIdAsc(service.getId());
        for (ServiceChecklist row : rows) {
            var d = row.getChecklist();
            subtotal = subtotal.add(calculator.price(policy, new ChecklistPriceCalculator.Input(d.getId(), d.getContent(), d.getDifficulty(), false,
                    row.getBasePriceOverride() != null ? row.getBasePriceOverride() : d.getBasePrice(),
                    row.getIncludedImageCountOverride() != null ? row.getIncludedImageCountOverride() : d.getIncludedImageCount(),
                    row.getIncludedVideoCountOverride() != null ? row.getIncludedVideoCountOverride() : d.getIncludedVideoCount(),
                    null, null, row.getPricingProfileOverride() != null ? row.getPricingProfileOverride() : d.getPricingProfile(),
                    d.getStandardVideoDurationSeconds(), true)).subtotal());
        }
        return of(subtotal);
    }

    /**
     * What to show for a service: the stored checklist sum when present (kept fresh by ServiceDefaultPriceService), otherwise
     * computed on the fly. Same decision rule as {@link #of}.
     */
    @Transactional(readOnly = true)
    public Resolved forDisplay(Service service) {
        if (service.getDefaultPrice() != null) return of(service.getDefaultPrice());
        return resolveOrLegacy(service);
    }

    /**
     * Lenient variant for read-only listings and estimates: without an active policy, or when a default checklist cannot
     * be priced, the service is shown as unpriced instead of failing the whole screen.
     */
    @Transactional(readOnly = true)
    public Resolved resolveOrLegacy(Service service) {
        try {
            PricingPolicy policy = policies.findEffectiveActive(Instant.now()).stream().findFirst().orElse(null);
            if (policy != null) return resolve(service, policy);
        } catch (ApiException ignored) {
            // fall through to unpriced
        }
        return of(BigDecimal.ZERO);
    }


    private static BigDecimal whole(BigDecimal v) { return v == null ? BigDecimal.ZERO : v.setScale(0, RoundingMode.HALF_UP); }
}
