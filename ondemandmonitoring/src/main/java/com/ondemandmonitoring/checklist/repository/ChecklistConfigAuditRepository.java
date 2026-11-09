package com.ondemandmonitoring.checklist.repository;

import com.ondemandmonitoring.checklist.domain.ChecklistConfigAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ChecklistConfigAuditRepository
        extends JpaRepository<ChecklistConfigAudit, String>, JpaSpecificationExecutor<ChecklistConfigAudit> {
}
