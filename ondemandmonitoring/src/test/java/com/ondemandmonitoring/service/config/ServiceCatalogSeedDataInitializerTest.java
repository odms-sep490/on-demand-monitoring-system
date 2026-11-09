package com.ondemandmonitoring.service.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.service.domain.DeliverableType;
import com.ondemandmonitoring.service.domain.Service;
import com.ondemandmonitoring.service.domain.ServiceDeliverable;
import com.ondemandmonitoring.service.repository.DeliverableTypeRepository;
import com.ondemandmonitoring.service.repository.ServiceDeliverableRepository;
import com.ondemandmonitoring.service.repository.ServiceRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ServiceCatalogSeedDataInitializerTest {

    @Test
    void seedsFourDefaultServicesWhenMissing() {
        ServiceRepository services = mock(ServiceRepository.class);
        DeliverableTypeRepository types = mock(DeliverableTypeRepository.class);
        ServiceDeliverableRepository links = mock(ServiceDeliverableRepository.class);

        when(services.findByCodeIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(services.findByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(services.findAll()).thenReturn(List.of());
        when(types.findByNameIgnoreCase(anyString())).thenReturn(Optional.of(activeType()));
        when(types.findAll()).thenReturn(List.of());
        when(links.findAll()).thenReturn(List.of());

        new ServiceCatalogSeedDataInitializer(services, types, links).run(null);

        verify(services, times(4)).save(any(Service.class));
        verify(services, never()).delete(any(Service.class));
    }

    @Test
    void existingCatalogIsUpdatedAndLegacyServicesAreSoftDeactivated() {
        ServiceRepository services = mock(ServiceRepository.class);
        DeliverableTypeRepository types = mock(DeliverableTypeRepository.class);
        ServiceDeliverableRepository links = mock(ServiceDeliverableRepository.class);

        Service construction = new Service();
        construction.setId("service-1");
        construction.setCode("CONSTRUCTION_MONITORING");
        construction.setName("Giám sát công trình");
        construction.setIsActive(true);

        Service legacy = new Service();
        legacy.setId("legacy-1");
        legacy.setCode("OLD_SERVICE");
        legacy.setName("Giám sát Kho bãi / Logistics");
        legacy.setIsActive(true);

        DeliverableType type = activeType();
        when(services.findByCodeIgnoreCase("CONSTRUCTION_MONITORING")).thenReturn(Optional.of(construction));
        when(services.findByCodeIgnoreCase("FACTORY_INSPECTION")).thenReturn(Optional.empty());
        when(services.findByCodeIgnoreCase("AREA_MONITORING")).thenReturn(Optional.empty());
        when(services.findByCodeIgnoreCase("FOREST_MONITORING")).thenReturn(Optional.empty());
        when(services.findByCodeIgnoreCase("OLD_SERVICE")).thenReturn(Optional.of(legacy));
        when(services.findByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(services.findAll()).thenReturn(List.of(construction, legacy));
        when(types.findByNameIgnoreCase(anyString())).thenReturn(Optional.of(type));
        when(types.findAll()).thenReturn(List.of(type));
        when(links.findAll()).thenReturn(List.of());

        new ServiceCatalogSeedDataInitializer(services, types, links).run(null);

        verify(services, atLeastOnce()).save(legacy);
        verify(links, atLeastOnce()).save(any(ServiceDeliverable.class));
    }

    private DeliverableType activeType() {
        DeliverableType type = DeliverableType.builder()
                .name("Ảnh chụp")
                .defaultFormat("IMAGE")
                .isActive(true)
                .build();
        type.setId("type-1");
        return type;
    }
}
