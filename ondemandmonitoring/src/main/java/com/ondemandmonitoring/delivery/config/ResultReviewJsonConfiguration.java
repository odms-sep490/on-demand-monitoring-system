package com.ondemandmonitoring.delivery.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Jackson 2 remains necessary for the persisted review snapshots used by Hibernate. */
@Configuration(proxyBeanMethods = false)
public class ResultReviewJsonConfiguration {
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper resultReviewObjectMapper() {
        return JsonMapper.builder().findAndAddModules().build();
    }
}
