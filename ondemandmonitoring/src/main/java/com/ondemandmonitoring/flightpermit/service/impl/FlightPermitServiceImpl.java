package com.ondemandmonitoring.flightpermit.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.devicecheck.service.IPersistedPreDeviceCheckService;
import com.ondemandmonitoring.finance.enums.DepositStatus;
import com.ondemandmonitoring.finance.enums.InvoiceStatus;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.flightpermit.service.IFlightPermitService;
import com.ondemandmonitoring.flightpermit.domain.FlightPermit;
import com.ondemandmonitoring.flightpermit.repository.FlightPermitRepository;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.dto.request.FlightPermitUpdateRequest;
import com.ondemandmonitoring.mission.dto.response.MissionResponse;
import com.ondemandmonitoring.mission.enums.FlightPermitStatus;
import com.ondemandmonitoring.mission.enums.MissionPurpose;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.enums.MissionStaffAssignmentStatus;
import com.ondemandmonitoring.mission.mapper.MissionMapper;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.repository.MissionStaffAssignmentRepository;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.LegalReviewStatus;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.refund.service.IRefundHoldService;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import java.util.Set;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class FlightPermitServiceImpl implements IFlightPermitService {
    private static final long RESOURCE_HOLD_HOURS = 24;

    MissionRepository missionRepository;
    FlightPermitRepository flightPermitRepository;
    OrderRepository orderRepository;
    InvoiceRepository invoiceRepository;
    MissionMapper missionMapper;
    MissionStaffAssignmentRepository missionStaffAssignmentRepository;
    AuthenticatedUserResolver authenticatedUserResolver;
    IPersistedPreDeviceCheckService persistedPreDeviceCheckService;
    @Autowired
    IRefundHoldService refundHoldService;

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canManageMissions()")
    public MissionResponse updateFlightPermit(String missionId, FlightPermitUpdateRequest request) {
        Mission mission = missionRepository.findByIdWithOrder(missionId)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        Order lockedOrder = orderRepository.findByIdForUpdate(mission.getOrder().getId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found"));
        if (refundHoldService != null) refundHoldService.requireOrderNotOnRefundHold(lockedOrder.getId());
        if (mission.getPurpose() == MissionPurpose.REWORK
                && mission.getStatus() == MissionStatus.PLANNING)
            throw new ApiException(ErrorCode.EXECUTION_PLAN_NOT_FINALIZED);
        var invoice = invoiceRepository.findByOrderId(mission.getOrder().getId())
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REQUEST,
                        "Flight permit updates require the order deposit to be paid"));
        if (invoice.getStatus() == InvoiceStatus.CANCELLED
                || invoice.getStatus() == InvoiceStatus.REFUNDED
                || invoice.getStatus() == InvoiceStatus.OVERDUE
                || invoice.effectiveDepositStatus() != DepositStatus.DEPOSIT_PAID) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Flight permit updates require the order deposit to be paid");
        }
        FlightPermit permit = flightPermitRepository.findByMissionId(missionId)
                .orElseGet(() -> FlightPermit.pendingFor(mission));
        if (Set.of(MissionStatus.IN_PROGRESS, MissionStatus.AWAITING_REVIEW, MissionStatus.PENDING_REVIEW,
                MissionStatus.COMPLETED, MissionStatus.FAILED, MissionStatus.CANCELLED)
                .contains(mission.getStatus())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Flight permit history cannot be changed after mission execution has started");
        }
        if (request.dataUrl() != null && request.dataUrl().length() > 7_000_000) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Flight permit file must not exceed 5 MB");
        }

        String note = blankToNull(request.note());
        if (request.status() == FlightPermitStatus.REJECTED && note == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "A rejection reason is required for a rejected flight permit");
        }
        if (request.status() == FlightPermitStatus.APPROVED) {
            if (blankToNull(request.dataUrl()) == null
                    && blankToNull(permit.getDataUrl()) == null) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "A flight permit file is required before approval");
            }
            var currentStaff = missionStaffAssignmentRepository
                    .findAllByMissionIdAndIsCurrentTrue(missionId).stream()
                    .filter(assignment -> assignment.getReleasedAt() == null)
                    .toList();
            if (currentStaff.isEmpty() || currentStaff.stream().anyMatch(assignment ->
                    assignment.getResponseStatus() != MissionStaffAssignmentStatus.ACCEPTED)) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "All assigned staff must accept the mission before the flight permit can be approved");
            }
        }
        if (request.status() == FlightPermitStatus.PENDING
                && permit.getStatus() == FlightPermitStatus.REJECTED
                && (request.dataUrl() == null || request.dataUrl().isBlank())
                && (note == null || note.equals(permit.getNote()))) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Add an updated permit document or remediation note before resubmitting");
        }

        permit.setStatus(request.status());
        permit.setNote(note);
        if (request.status() == FlightPermitStatus.REJECTED) {
            permit.setResourceHoldUntil(
                    Instant.now().plusSeconds(RESOURCE_HOLD_HOURS * 3600));
            permit.setResourcesReleasedAt(null);
        } else if (request.status() == FlightPermitStatus.APPROVED) {
            permit.setResourceHoldUntil(null);
            permit.setResourcesReleasedAt(null);
        } else if (request.status() == FlightPermitStatus.PENDING) {
            permit.setResourcesReleasedAt(null);
        }
        if (request.dataUrl() != null && !request.dataUrl().isBlank()) {
            permit.setFileName(request.fileName());
            permit.setContentType(request.contentType());
            permit.setDataUrl(request.dataUrl());
        }

        flightPermitRepository.save(permit);
        Mission saved = missionRepository.save(mission);
        persistedPreDeviceCheckService.refreshFlightPermitCheck(missionId);
        synchronizeOrderLegalReview(saved);
        return missionMapper.toResponse(saved);
    }

    @Override
    public void requireApprovedForExecution(Mission mission) {
        if (refundHoldService != null) refundHoldService.requireOrderNotOnRefundHold(mission.getOrder().getId());
        if (mission.getFlightPermit() == null
                || mission.getFlightPermit().getStatus() != FlightPermitStatus.APPROVED) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Flight permit must be approved before the mission can start");
        }
    }

    private void synchronizeOrderLegalReview(Mission mission) {
        Order order = mission.getOrder();
        FlightPermit permit = mission.getFlightPermit();
        FlightPermitStatus status = permit == null ? FlightPermitStatus.PENDING : permit.getStatus();
        if (status == FlightPermitStatus.REJECTED) {
            order.getFlightAreaReview().setLegalReviewStatus(LegalReviewStatus.REJECTED);
            order.getFlightAreaReview().setFlightAreaReviewReason("FLIGHT_PERMIT_REJECTED");
            order.getFlightAreaReview().setFlightAreaReviewNote(permit.getNote());
        } else if (status == FlightPermitStatus.PENDING) {
            order.getFlightAreaReview().setLegalReviewStatus(LegalReviewStatus.PENDING_PERMIT);
            order.getFlightAreaReview().setFlightAreaReviewReason("FLIGHT_PERMIT_RESUBMITTED");
            order.getFlightAreaReview().setFlightAreaReviewNote(permit.getNote());
        } else if (status == FlightPermitStatus.APPROVED) {
            boolean allApproved = missionRepository
                    .findAllByOrderIdOrderByScheduledStartAtAscIdAsc(order.getId()).stream()
                    .filter(item -> item.getStatus() != MissionStatus.CANCELLED)
                    .allMatch(item -> item.getFlightPermit() != null
                            && item.getFlightPermit().getStatus() == FlightPermitStatus.APPROVED);
            order.getFlightAreaReview().setLegalReviewStatus(allApproved
                    ? LegalReviewStatus.APPROVED
                    : LegalReviewStatus.PENDING_PERMIT);
            order.getFlightAreaReview().setFlightAreaReviewReason(allApproved ? null : "WAITING_FOR_OTHER_MISSION_PERMITS");
            order.getFlightAreaReview().setFlightAreaReviewNote(allApproved ? null : order.getFlightAreaReview().getFlightAreaReviewNote());
        }
        order.getFlightAreaReview().setFlightAreaReviewedBy(authenticatedUserResolver.getCurrentUser());
        order.getFlightAreaReview().setFlightAreaReviewedAt(Instant.now());
        orderRepository.save(order);
        // A rejected permit blocks execution; it does not itself reject or cancel the Order.
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
