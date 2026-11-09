package com.ondemandmonitoring.checklist.repository;

import com.ondemandmonitoring.checklist.domain.ServiceChecklist;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;

public interface ServiceChecklistRepository extends JpaRepository<ServiceChecklist, String> {
    @Query("select sc.checklist.id from ServiceChecklist sc where sc.service.id = :serviceId order by sc.displayOrder, sc.id")
    List<String> findTemplateChecklistIds(@org.springframework.data.repository.query.Param("serviceId") String serviceId);
    /** Scalar projection on purpose: loading entities here could leave stale catalog state in the persistence context. */
    @Query("select sc.checklist.id as checklistId, sc.basePriceOverride as basePriceOverride, "
            + "sc.includedImageCountOverride as includedImageCountOverride, sc.includedVideoCountOverride as includedVideoCountOverride, "
            + "sc.pricingProfileOverride as pricingProfileOverride, sc.required as required, sc.checklist.isActive as checklistActive "
            + "from ServiceChecklist sc where sc.service.id = :serviceId")
    List<TemplateOverride> findTemplateOverrides(@org.springframework.data.repository.query.Param("serviceId") String serviceId);

    interface TemplateOverride {
        String getChecklistId();
        java.math.BigDecimal getBasePriceOverride();
        Integer getIncludedImageCountOverride();
        Integer getIncludedVideoCountOverride();
        String getPricingProfileOverride();
        boolean isRequired();
        Boolean getChecklistActive();
    }
    /** How many services use each checklist (grouped, so a page of checklists costs one query). */
    @Query("select sc.checklist.id as checklistId, count(sc) as total from ServiceChecklist sc "
            + "where sc.checklist.id in :ids group by sc.checklist.id")
    List<UsageCount> countByChecklistIds(@org.springframework.data.repository.query.Param("ids") java.util.Collection<String> ids);

    interface UsageCount {
        String getChecklistId();
        long getTotal();
    }
    boolean existsByServiceIdAndChecklistId(String serviceId, String checklistId);
    Optional<ServiceChecklist> findByServiceIdAndChecklistId(String serviceId, String checklistId);
    @EntityGraph(attributePaths = {"service", "checklist"})
    List<ServiceChecklist> findAllByServiceIdOrderByDisplayOrderAscIdAsc(String serviceId);
    @EntityGraph(attributePaths = {"service", "checklist"})
    List<ServiceChecklist> findAllByServiceIdAndChecklistIsActiveTrueOrderByDisplayOrderAscIdAsc(String serviceId);
    @EntityGraph(attributePaths = {"service", "checklist"})
    List<ServiceChecklist> findAllByChecklistIdOrderByServiceNameAscServiceIdAsc(String checklistId);
}
