package com.ondemandmonitoring.testsupport;

import java.util.List;
import java.util.Locale;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Safety net for every Spring Boot test: refuses to start when the resolved datasource points at the shared development
 * database (host port 5433). Tests must use a disposable database; this fails before any connection is opened.
 */
public class DevDatabaseGuard implements EnvironmentPostProcessor {
    private static final List<String> LOCAL_HOSTS = List.of("localhost", "127.0.0.1", "[::1]", "host.docker.internal", "0.0.0.0");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String url = environment.getProperty("spring.datasource.url");
        if (url == null) return;
        String normalized = url.toLowerCase(Locale.ROOT);
        for (String host : LOCAL_HOSTS) {
            if (normalized.contains("//" + host + ":5433/") || normalized.endsWith("//" + host + ":5433")) {
                throw new IllegalStateException("Refusing to run a test against the development database port 5433. "
                        + "Set SPRING_DATASOURCE_URL to a disposable test database (see _local-run/backend-test-env.ps1).");
            }
        }
    }
}
