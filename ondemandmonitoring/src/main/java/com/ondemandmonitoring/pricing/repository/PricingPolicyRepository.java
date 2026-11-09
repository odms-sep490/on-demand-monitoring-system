package com.ondemandmonitoring.pricing.repository;

import com.ondemandmonitoring.pricing.domain.PricingPolicy;
import com.ondemandmonitoring.pricing.enums.PricingPolicyStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface PricingPolicyRepository extends JpaRepository<PricingPolicy, String> {
    List<PricingPolicy> findAllByOrderByPolicyVersionDesc();
    @Query("select distinct p from PricingPolicy p left join fetch p.rules where p.status = com.ondemandmonitoring.pricing.enums.PricingPolicyStatus.ACTIVE and (p.effectiveFrom is null or p.effectiveFrom <= :now) and (p.effectiveTo is null or p.effectiveTo > :now) order by p.policyVersion desc")
    List<PricingPolicy> findEffectiveActive(@Param("now") Instant now);
    @Query("select distinct p from PricingPolicy p left join fetch p.rules where p.id = :id")
    Optional<PricingPolicy> findWithRulesById(@Param("id") String id);
    Optional<PricingPolicy> findFirstByStatusOrderByPolicyVersionDesc(PricingPolicyStatus status);
    List<PricingPolicy> findAllByStatus(PricingPolicyStatus status);
    boolean existsByPolicyVersion(int version);
}
