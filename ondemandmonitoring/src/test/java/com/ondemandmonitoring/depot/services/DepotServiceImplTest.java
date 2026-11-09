package com.ondemandmonitoring.depot.services;

import com.ondemandmonitoring.depot.domain.Depot;
import com.ondemandmonitoring.depot.enums.DepotStatus;
import com.ondemandmonitoring.depot.mapper.DepotMapper;
import com.ondemandmonitoring.depot.repositories.DepotRepository;
import com.ondemandmonitoring.depot.services.Impl.DepotServiceImpl;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.order.util.GeoReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DepotServiceImplTest {
    @Mock DepotRepository depotRepository;
    @Mock OrderRepository orderRepository;
    @Mock DepotMapper depotMapper;

    @InjectMocks DepotServiceImpl service;

    @Test
    void deleteDepotDeactivatesWithoutDeleting() {
        Depot depot = new Depot();
        depot.setStatus(DepotStatus.ACTIVE);
        when(depotRepository.findById("depot-1")).thenReturn(Optional.of(depot));

        service.deleteDepot("depot-1");

        assertEquals(DepotStatus.INACTIVE, depot.getStatus());
        verify(depotRepository, never()).delete(any(Depot.class));
    }

    @Test
    void nearestDepotQueriesOrderLocationAndConvertsMetersToKm() {
        Order order = new Order();
        order.setPoint(GeoReader.createPoint(106.7, 10.8));
        Depot depot = new Depot();
        depot.setId("depot-1");
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));
        when(depotRepository.findNearestActiveDepot(106.7, 10.8))
            .thenReturn(Optional.of(depot));
        when(depotRepository.calculateDistanceMeters("depot-1", 106.7, 10.8))
            .thenReturn(3456.0);

        service.findNearestDepot("order-1");

        verify(depotMapper).toResponse(depot, 3.46);
    }
}
