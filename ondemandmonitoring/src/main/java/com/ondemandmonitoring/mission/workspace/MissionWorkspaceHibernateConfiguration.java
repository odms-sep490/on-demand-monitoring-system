package com.ondemandmonitoring.mission.workspace;

import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.jpa.boot.spi.IntegratorProvider;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
public class MissionWorkspaceHibernateConfiguration {
    @Bean
    HibernatePropertiesCustomizer missionWorkspaceHibernate(ObjectProvider<MissionWorkspaceWriteListener> listener) {
        return properties -> {
            IntegratorProvider existing = (IntegratorProvider) properties.get("hibernate.integrator_provider");
            properties.put("hibernate.integrator_provider", (IntegratorProvider) () -> {
                List<Integrator> integrators = new ArrayList<>();
                if (existing != null) integrators.addAll(existing.getIntegrators());
                integrators.add(integrator(listener.getObject()));
                return integrators;
            });
        };
    }

    static Integrator integrator(MissionWorkspaceWriteListener listener) {
        return new Integrator() {
            @Override public void integrate(Metadata metadata, BootstrapContext context, SessionFactoryImplementor factory) {
                var registry = factory.getServiceRegistry().getService(EventListenerRegistry.class);
                registry.appendListeners(EventType.POST_INSERT, listener);
                registry.appendListeners(EventType.POST_UPDATE, listener);
                registry.appendListeners(EventType.PRE_DELETE, listener);
                registry.appendListeners(EventType.POST_COLLECTION_UPDATE, listener);
                registry.appendListeners(EventType.POST_COLLECTION_RECREATE, listener);
                registry.appendListeners(EventType.PRE_COLLECTION_REMOVE, listener);
            }
        };
    }
}
