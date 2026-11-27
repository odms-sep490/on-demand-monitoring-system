package com.ondemandmonitoring.service.config;

import com.ondemandmonitoring.service.domain.*;
import com.ondemandmonitoring.service.repository.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ServiceCatalogSeedDataInitializerTest {
    ServiceRepository services = mock(ServiceRepository.class);
    DeliverableTypeRepository types = mock(DeliverableTypeRepository.class);
    ServiceDeliverableRepository links = mock(ServiceDeliverableRepository.class);
    ServiceCatalogBootstrapRepository bootstrap = mock(ServiceCatalogBootstrapRepository.class);
    ServiceCatalogSeedDataInitializer seed = new ServiceCatalogSeedDataInitializer(services, bootstrap, types, links);

    @Test void freshDatabaseSeedsFourServicesOnce() {
        when(bootstrap.claimInitialization()).thenReturn(1, 0);
        seed.run(null); seed.run(null);
        verify(services, times(4)).save(any(Service.class));
        verify(types, times(3)).save(any(DeliverableType.class));
        verify(services, never()).findAll();
        verify(services, never()).delete(any(Service.class));
    }
    @Test void restartDoesNotResurrectDeletedServicesOrReactivateOrPruneLinks() {
        when(bootstrap.claimInitialization()).thenReturn(0);
        seed.run(null);
        verifyNoInteractions(services, types, links);
    }
    @Test void existingCatalogIsAdoptedWithoutChangingAdminOrCustomRows() {
        when(bootstrap.claimInitialization()).thenReturn(1);
        when(services.count()).thenReturn(1L);
        seed.run(null);
        verify(services).count(); verifyNoMoreInteractions(services);
        verifyNoInteractions(types, links);
    }
}
