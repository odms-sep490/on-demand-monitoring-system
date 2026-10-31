package com.ondemandmonitoring.finance.repository;

import com.ondemandmonitoring.finance.domain.CommercialExpirationSettingsAudit;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommercialExpirationSettingsAuditRepository
        extends JpaRepository<CommercialExpirationSettingsAudit, String> {
    List<CommercialExpirationSettingsAudit> findAllByOrderByChangedAtDesc(Pageable pageable);
}
