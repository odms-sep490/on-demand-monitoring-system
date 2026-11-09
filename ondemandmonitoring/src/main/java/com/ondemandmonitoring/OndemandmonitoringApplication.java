package com.ondemandmonitoring;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@EnableJpaAuditing
@EnableScheduling
@SpringBootApplication
public class OndemandmonitoringApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(OndemandmonitoringApplication.class, args);
        var environment = context.getEnvironment();
        String port = environment.getProperty("local.server.port",
                environment.getProperty("server.port", "8080"));
        String contextPath = environment.getProperty("server.servlet.context-path", "");
        String swaggerPath = environment.getProperty("springdoc.swagger-ui.path", "/swagger-ui.html");
        String swaggerUrl = "http://localhost:%s%s%s".formatted(port, normalize(contextPath), normalize(swaggerPath));
        log.info("Swagger UI: {}", swaggerUrl);
    }

    private static String normalize(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) return "";
        return path.startsWith("/") ? path : "/" + path;
    }
}
