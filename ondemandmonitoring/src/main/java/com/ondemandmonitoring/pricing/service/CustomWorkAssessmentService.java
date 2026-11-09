package com.ondemandmonitoring.pricing.service;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.pricing.domain.CustomWorkAssessment;
import com.ondemandmonitoring.pricing.dto.*;
import com.ondemandmonitoring.pricing.repository.CustomWorkAssessmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class CustomWorkAssessmentService {
    private final CustomWorkAssessmentRepository assessments;
    private final OrderRepository orders;
    @Transactional(readOnly = true)
    public CustomWorkAssessmentResponse get(String orderId) { return assessments.findByOrderId(orderId).map(this::response).orElse(null); }
    @Transactional
    public CustomWorkAssessmentResponse save(String orderId, CustomWorkAssessmentRequest r) {
        Order order = orders.findByIdForUpdate(orderId).or(() -> orders.findById(orderId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        long effectiveOrderRev = order.getChecklistRevision() == 0 ? 1 : order.getChecklistRevision();
        if (r.expectedChecklistRevision() != null
                && r.expectedChecklistRevision() != effectiveOrderRev
                && r.expectedChecklistRevision() != order.getChecklistRevision()) {
            throw new ApiException(ErrorCode.STALE_PRICING_REVISION);
        }
        CustomWorkAssessment a = assessments.findByOrderId(orderId).orElseGet(() -> {
            CustomWorkAssessment created = new CustomWorkAssessment();
            created.setOrder(order); return created;
        });
        a.setEstimatedFlightHours(r.estimatedFlightHours()); a.setEstimatedDistanceKm(r.estimatedDistanceKm()); a.setEstimatedAreaHa(r.estimatedAreaHa());
        a.setPilotCount(r.pilotCount()); a.setDroneCount(r.droneCount()); a.setBatteryUnits(r.batteryUnits()); a.setTerrain(r.terrain());
        a.setThermalRequired(r.thermalRequired()); a.setMultispectralRequired(r.multispectralRequired());
        a.setNightOperation(r.nightOperation()); a.setSpecialEquipment(trim(r.specialEquipment())); a.setOtherCostDrivers(trim(r.otherCostDrivers()));
        a.setAssessmentComplete(r.assessmentComplete()); return response(assessments.save(a));
    }
    private CustomWorkAssessmentResponse response(CustomWorkAssessment a) { return new CustomWorkAssessmentResponse(a.getId(), a.getOrder().getId(), a.getEstimatedFlightHours(), a.getEstimatedDistanceKm(), a.getEstimatedAreaHa(), a.getPilotCount(), a.getDroneCount(), a.getBatteryUnits(), a.getTerrain(), a.isThermalRequired(), a.isMultispectralRequired(), a.isNightOperation(), a.getSpecialEquipment(), a.getOtherCostDrivers(), a.isAssessmentComplete(), a.getVersion() == null ? 0 : a.getVersion()); }
    private String trim(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
