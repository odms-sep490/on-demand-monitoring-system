package com.ondemandmonitoring.audit.repository;

import com.ondemandmonitoring.audit.domain.AdminAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AdminAuditLogRepository
        extends JpaRepository<AdminAuditLog, String>, JpaSpecificationExecutor<AdminAuditLog> {
}
