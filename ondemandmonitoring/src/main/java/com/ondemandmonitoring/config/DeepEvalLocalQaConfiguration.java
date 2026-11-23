package com.ondemandmonitoring.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Explicit local-only QA profile: isolate data and prevent background side effects. */
@Configuration
@Profile("deepeval-local")
public class DeepEvalLocalQaConfiguration {
    @Bean
    static BeanFactoryPostProcessor deepEvalLocalQaIsolation(Environment environment) {
        String schema=environment.getProperty("spring.jpa.properties.hibernate.default_schema","");
        String url=environment.getProperty("spring.datasource.url","");
        if(!schema.matches("odm_deepeval_[a-z0-9_]+")
                || !url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/.*")
                || !url.contains("currentSchema="+schema)) {
            throw new IllegalStateException("DeepEval QA requires an isolated odm_deepeval_* schema on localhost");
        }
        return factory -> {
            // No expiry/outbox/payment/media dispatcher is allowed to run against
            // copied local data. HTTP business validation remains unchanged.
            String name="org.springframework.scheduling.config.internalScheduledAnnotationProcessor";
            if(factory instanceof BeanDefinitionRegistry registry && registry.containsBeanDefinition(name)) {
                registry.removeBeanDefinition(name);
            }
        };
    }
}
