package com.ondemandmonitoring.media.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Detect broken aggregate ownership; schema setup is Hibernate create plus the fresh bootstrap. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class MediaDetailSchemaGuard implements CommandLineRunner {
    private final JdbcTemplate jdbc;

    @Override
    public void run(String... args) {
        Boolean missing = jdbc.queryForObject("""
                select exists(select 1 from drone_media m
                    left join media_derivatives d on d.media_id=m.id where d.media_id is null)
                """, Boolean.class);
        if (Boolean.TRUE.equals(missing)) throw new IllegalStateException(
                "Media detail invariant violated: each media asset must own a derivative row. Verify the fresh schema and media write path.");
    }
}
