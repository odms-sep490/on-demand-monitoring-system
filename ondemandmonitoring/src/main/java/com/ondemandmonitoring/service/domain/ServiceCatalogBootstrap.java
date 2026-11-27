package com.ondemandmonitoring.service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Survives deletion of every catalog row, so restarts cannot resurrect administrator deletions. */
@Entity
@Table(name = "service_catalog_bootstrap")
public class ServiceCatalogBootstrap {
    @Id @Column(length = 80, nullable = false)
    private String id;
    @Column(name = "initialized_at", nullable = false)
    private Instant initializedAt;
}
