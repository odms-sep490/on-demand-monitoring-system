package com.ondemandmonitoring.service.repository;

import com.ondemandmonitoring.service.domain.ServiceCatalogBootstrap;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ServiceCatalogBootstrapRepository extends JpaRepository<ServiceCatalogBootstrap, String> {
    /** The unique marker serializes concurrent startup transactions; rollback releases the claim. */
    @Modifying
    @Query(value = "INSERT INTO service_catalog_bootstrap (id, initialized_at) VALUES ('default', CURRENT_TIMESTAMP) ON CONFLICT (id) DO NOTHING", nativeQuery = true)
    int claimInitialization();
}
