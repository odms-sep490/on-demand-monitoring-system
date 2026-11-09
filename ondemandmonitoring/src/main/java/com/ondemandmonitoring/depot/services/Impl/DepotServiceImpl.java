
package com.ondemandmonitoring.depot.services.Impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.depot.domain.Depot;
import com.ondemandmonitoring.depot.dto.request.DepotCreateRequest;
import com.ondemandmonitoring.depot.dto.request.DepotUpdateRequest;
import com.ondemandmonitoring.depot.dto.response.DepotResponse;
import com.ondemandmonitoring.depot.enums.DepotStatus;
import com.ondemandmonitoring.depot.mapper.DepotMapper;
import com.ondemandmonitoring.depot.repositories.DepotRepository;
import com.ondemandmonitoring.depot.services.DepotService;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.repository.OrderRepository;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class DepotServiceImpl implements DepotService {

    DepotRepository depotRepository;
    OrderRepository orderRepository;
    DepotMapper depotMapper;

    @Override
    @Transactional
    public DepotResponse createDepot(DepotCreateRequest request) {

        Depot depot = depotMapper.toEntity(request);
        depot.setStatus(DepotStatus.ACTIVE);

        Depot savedDepot = depotRepository.save(depot);

        return depotMapper.toResponse(savedDepot);
    }

    @Override
    @Transactional(readOnly = true)
    public DepotResponse getDepotById(String id) {

        Depot depot = findDepotOrThrow(id);

        return depotMapper.toResponse(depot);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DepotResponse> getAllDepots() {

        return depotRepository.findAll()
                .stream()
                .map(depotMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public DepotResponse updateDepot(
            String id,
            DepotUpdateRequest request
    ) {

        Depot depot = findDepotOrThrow(id);

        depotMapper.updateEntity(request, depot);

        Depot savedDepot = depotRepository.save(depot);

        return depotMapper.toResponse(savedDepot);
    }

    @Override
    @Transactional
    public void deleteDepot(String id) {

        Depot depot = findDepotOrThrow(id);

        depot.setStatus(DepotStatus.INACTIVE);
    }

    @Override
    @Transactional(readOnly = true)
    public DepotResponse findNearestDepot(String orderId) {

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Order not found: " + orderId
                ));

        Point point = order.getPoint();

        if (point == null || point.isEmpty()) {
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "Order location is invalid"
            );
        }

        double longitude = point.getX();
        double latitude = point.getY();

        Depot nearestDepot = depotRepository
                .findNearestActiveDepot(longitude, latitude)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "No active depot found"
                ));

        Double distanceMeters =
                depotRepository.calculateDistanceMeters(
                        nearestDepot.getId(),
                        longitude,
                        latitude
                );

        Double distanceKm = distanceMeters == null
                ? null
                : Math.round(distanceMeters / 10.0) / 100.0;

        return depotMapper.toResponse(nearestDepot, distanceKm);
    }

    private Depot findDepotOrThrow(String id) {

        return depotRepository.findById(id)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Depot not found: " + id
                ));
    }
}
