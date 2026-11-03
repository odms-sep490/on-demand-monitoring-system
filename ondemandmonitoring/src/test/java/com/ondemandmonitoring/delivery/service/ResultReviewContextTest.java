package com.ondemandmonitoring.delivery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.delivery.config.ResultReviewJsonConfiguration;
import com.ondemandmonitoring.delivery.service.impl.ResultReviewRoundPublisher;
import com.ondemandmonitoring.delivery.service.impl.ResultReviewService;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class ResultReviewContextTest {
    @Test
    void createsBothReviewServicesWithoutJackson2AutoConfiguration() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(ResultReviewJsonConfiguration.class);
            Set<Class<?>> dependencies = new HashSet<>();
            for (Class<?> service : new Class<?>[] {ResultReviewRoundPublisher.class, ResultReviewService.class}) {
                for (Class<?> dependency : service.getConstructors()[0].getParameterTypes()) {
                    if (dependency != ObjectMapper.class && dependencies.add(dependency)) {
                        registerMock(context, dependency);
                    }
                }
                context.register(service);
            }
            context.refresh();

            assertThat(context.getBean(ResultReviewRoundPublisher.class)).isNotNull();
            assertThat(context.getBean(ResultReviewService.class)).isNotNull();
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            assertThat(mapper.valueToTree(java.util.Map.of("revision", 1)).path("revision").asInt()).isEqualTo(1);
            assertThat(mapper.writeValueAsString(Instant.EPOCH)).isNotBlank();
        }
    }

    private static <T> void registerMock(AnnotationConfigApplicationContext context, Class<T> dependency) {
        context.registerBean(dependency.getName(), dependency, () -> mock(dependency));
    }
}
