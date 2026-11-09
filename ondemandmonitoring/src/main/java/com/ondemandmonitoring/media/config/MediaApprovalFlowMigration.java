package com.ondemandmonitoring.media.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Removes the legacy per-file manager approval gate for already validated media. */
@Component
@RequiredArgsConstructor
public class MediaApprovalFlowMigration implements CommandLineRunner {
    private final JdbcTemplate jdbc;

    @Override
    public void run(String... args) {
        jdbc.update("""
                update drone_media
                   set media_status = 'AVAILABLE',
                       available_at = coalesce(available_at, validated_at, current_timestamp)
                 where media_status = 'PENDING_MANAGER_APPROVAL'
                   and validated_at is not null
                   and validation_error is null
                """);
    }
}
