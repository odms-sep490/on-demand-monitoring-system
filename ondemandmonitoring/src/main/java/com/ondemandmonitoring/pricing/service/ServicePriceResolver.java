package com.ondemandmonitoring.pricing.service;

import com.ondemandmonitoring.checklist.domain.ServiceChecklist;
import com.ondemandmonitoring.checklist.repository.ServiceChecklistRepository;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.service.domain.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single rule for "what does a Service cost by default":
 * <pre>
 * default price = sum(price of each active default checklist at its standard area)
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

    /** @param packageFee always 0: the checklists are the whole price. Kept for response compatibility. */
    public record Resolved(String source, BigDecimal packageFee, BigDecimal checklistSubtotal, BigDecimal total) {}

    /** Decision from the amount the default checklists add up to. */
    public static Resolved of(BigDecimal checklistSubtotal) {
        BigDecimal subtotal = whole(checklistSubtotal);
        return new Resolved(subtotal.signum() > 0 ? CHECKLISTS : UNPRICED, BigDecimal.ZERO, subtotal, subtotal);
    }

    /** Strict: pricing problems of a default checklist surface as {@link ApiException}. */
    @Transactional(readOnly = true)
    public Resolved resolve(Service service) {
        BigDecimal subtotal = BigDecimal.ZERO;
        List<ServiceChecklist> rows = assignments.findAllByServiceIdAndChecklistIsActiveTrueOrderByDisplayOrderAscIdAsc(service.getId());
        for (ServiceChecklist row : rows) {
            var d = row.getChecklist();
            if (d.getStandardAreaM2() == null || d.getStandardAreaM2().signum() <= 0)
                throw new ApiException(com.ondemandmonitoring.common.exception.ErrorCode.PRICING_CALCULATION_FAILED,
                        "Configure a positive standard area for checklist: " + d.getContent());
            subtotal = subtotal.add(whole(d.getBasePrice()));
        }
        return of(subtotal);
    }

    /**
     * What to show for a service: the stored checklist sum when present (kept fresh by ServiceDefaultPriceService), otherwise
     * computed on the fly. Same decision rule as {@link #of}.
     */
    @Transactional(readOnly = true)
    public Resolved forDisplay(Service service) {
        return resolveOrLegacy(service);
    }

    /**
     * Lenient variant for read-only listings and estimates: when a default checklist cannot be priced, the service is
     * shown as unpriced instead of failing the whole screen.
     */
    @Transactional(readOnly = true)
    public Resolved resolveOrLegacy(Service service) {
        try {
            return resolve(service);
        } catch (ApiException ignored) {
            // fall through to unpriced
        }
        return of(BigDecimal.ZERO);
    }


    private static BigDecimal whole(BigDecimal v) { return v == null ? BigDecimal.ZERO : v.setScale(0, RoundingMode.HALF_UP); }
}
