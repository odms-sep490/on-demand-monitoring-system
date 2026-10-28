package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.devicecheck.repository.PersistedPostDeviceCheckRepository;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.dto.request.*;
import com.ondemandmonitoring.mission.dto.response.MissionSafeStopResponse;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.*;
import com.ondemandmonitoring.order.enums.*;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.ondemandmonitoring.userschedule.enums.*;
import com.ondemandmonitoring.userschedule.repository.UserScheduleRepository;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** DB bookkeeping only. No claim to command PX4 or verify landing remotely: assigned crew explicitly attest it. */
@Service
@RequiredArgsConstructor
@Transactional
public class MissionSafeStopServiceImpl implements IMissionSafeStopService {
    private final OrderRepository orders;
    private final MissionRepository missions;
    private final MissionResultRepository results;
    private final MissionDeviceAssignmentRepository devices;
    private final MissionStaffAssignmentRepository crew;
    private final DeviceConnectionRepository connections;
    private final FlightTokenRepository tokens;
    private final UserScheduleRepository schedules;
    private final PersistedPostDeviceCheckRepository inspections;
    private final IResourceReservationService resources;
    private final AuthenticatedUserResolver actor;

    private Mission lock(String id) {
        String orderId = missions.findOrderIdByMissionId(id).orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        orders.findByIdForUpdate(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        return missions.findByIdForUpdate(id).orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
    }
    private void acceptedHistoryGuard(Mission m) {
        if (m.getStatus() == MissionStatus.CANCELLED || results.existsByMissionIdAndApprovalStatus(m.getId(), MissionResultApprovalStatus.APPROVED))
            throw new ApiException(ErrorCode.MISSION_CANCELLATION_NOT_ALLOWED);
    }

    private void requireRequestAllowed(Mission m, long expectedRevision) {
        var order = m.getOrder();
        if (order.getExecutionPlanStatus() != ExecutionPlanStatus.DRAFT) throw new ApiException(ErrorCode.EXECUTION_PLAN_NOT_DRAFT);
        if (order.getExecutionPlanRevision() != expectedRevision) throw new ApiException(ErrorCode.EXECUTION_PLAN_REVISION_CONFLICT);
        if (order.getOrderStatus() != OrderStatus.APPROVED && order.getOrderStatus() != OrderStatus.IN_PROGRESS)
            throw new ApiException(ErrorCode.MISSION_CANCELLATION_NOT_ALLOWED);
    }

    private void requireConfirmationAllowed(Mission m, com.ondemandmonitoring.user.domain.User user) {
        boolean authorized = user.getRole() != null && user.getRole().getCode() == RoleCode.STAFF
                && crew.findByMissionId(m.getId()).stream().anyMatch(a -> a.getStaff() != null && user.getId().equals(a.getStaff().getId())
                    && Boolean.TRUE.equals(a.getIsCurrent()) && a.getResponseStatus() == MissionStaffAssignmentStatus.ACCEPTED
                    && (a.getAssignedRole() == MissionStaffRole.PILOT
                        || (!Boolean.TRUE.equals(m.getSafeStopRequiresLanding()) && a.getAssignedRole() == MissionStaffRole.OPERATOR)));
        if (!authorized) throw new ApiException(ErrorCode.ACCESS_DENIED);
        if (m.getSafeStopRequestedAt() == null || Set.of(MissionStatus.IN_FLIGHT, MissionStatus.IN_PROGRESS,
                MissionStatus.RETURNING, MissionStatus.CONNECTED, MissionStatus.PREFLIGHT_CHECKING,
                MissionStatus.READY_TO_FLY, MissionStatus.POSTFLIGHT_CHECKING).contains(m.getStatus()))
            throw new ApiException(ErrorCode.MISSION_SAFE_STOP_NOT_READY);
    }

    private void requireCleanupRequested(Mission m) {
        if (m.getSafeStopRequestedAt() == null || m.getSafeStopStatus() == MissionSafeStopStatus.FAILED)
            throw new ApiException(ErrorCode.MISSION_SAFE_STOP_FAILED);
    }

    private void requireCleanupReady(Mission m, List<com.ondemandmonitoring.mission.domain.DeviceConnection> sessions) {
        boolean live = sessions.stream().anyMatch(s -> "CONNECTED".equals(s.getConnectionStatus()) || Boolean.TRUE.equals(s.getTelemetryActive()));
        if ((Boolean.TRUE.equals(m.getSafeStopRequiresLanding()) || live) && m.getSafeStopConfirmedAt() == null)
            throw new ApiException(ErrorCode.MISSION_SAFE_STOP_NOT_READY);
        if (Set.of(MissionStatus.IN_FLIGHT, MissionStatus.IN_PROGRESS, MissionStatus.RETURNING,
                MissionStatus.CONNECTED, MissionStatus.PREFLIGHT_CHECKING, MissionStatus.READY_TO_FLY,
                MissionStatus.POSTFLIGHT_CHECKING).contains(m.getStatus())) throw new ApiException(ErrorCode.MISSION_SAFE_STOP_NOT_READY);
        if (Boolean.TRUE.equals(m.getSafeStopRequiresLanding()) && m.getStatus() != MissionStatus.FAILED
                && inspections.findFirstByMissionIdOrderByCreatedAtDesc(m.getId()).filter(p -> p.getCompletedAt() != null
                        && (m.getActualStartAt() == null || !p.getCompletedAt().isBefore(m.getActualStartAt()))
                        && p.getDeviceConnection() != null && m.getId().equals(p.getDeviceConnection().getMission().getId())).isEmpty())
            throw new ApiException(ErrorCode.MISSION_SAFE_STOP_NOT_READY);
    }

    @Override @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public MissionSafeStopResponse request(String id, MissionSafeStopRequest request) {
        if (request == null || request.expectedPlanRevision() == null || request.reason() == null
                || request.reason().isBlank() || request.reason().length() > 1000) throw new ApiException(ErrorCode.INVALID_REQUEST);
        var m = lock(id); acceptedHistoryGuard(m);
        requireRequestAllowed(m, request.expectedPlanRevision());
        if (m.getSafeStopRequestedAt() == null) {
            m.setSafeStopStatus(MissionSafeStopStatus.REQUESTED); m.setSafeStopRequestedAt(Instant.now());
            m.setSafeStopRequestedBy(actor.getCurrentUserId()); m.setSafeStopReason(request.reason().strip());
            m.setSafeStopRequiresLanding(m.getActualStartAt() != null || Set.of(MissionStatus.IN_FLIGHT,
                    MissionStatus.IN_PROGRESS, MissionStatus.RETURNING, MissionStatus.FAILED).contains(m.getStatus()));
            missions.saveAndFlush(m);
        }
        return response(m);
    }

    @Override @PreAuthorize("hasRole('STAFF')")
    public MissionSafeStopResponse confirm(String id, MissionSafeStopConfirmationRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.landedAndDisarmed()) || !Boolean.TRUE.equals(request.controlSessionClosed())
                || request.confirmation() == null || request.confirmation().isBlank() || request.confirmation().length() > 1000)
            throw new ApiException(ErrorCode.MISSION_SAFE_STOP_NOT_READY);
        var m = lock(id); acceptedHistoryGuard(m);
        var user = actor.getCurrentUser();
        requireConfirmationAllowed(m, user);
        if (m.getSafeStopConfirmedAt() == null) {
            m.setSafeStopStatus(MissionSafeStopStatus.IN_PROGRESS); m.setSafeStopConfirmedAt(Instant.now());
            m.setSafeStopConfirmedBy(user.getId()); m.setSafeStopConfirmation(request.confirmation().strip()); missions.saveAndFlush(m);
        }
        return response(m);
    }

    @Override @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public MissionSafeStopResponse fail(String id, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 1000) throw new ApiException(ErrorCode.INVALID_REQUEST);
        var m = lock(id); acceptedHistoryGuard(m);
        if (m.getSafeStopRequestedAt() == null || m.getSafeStopConfirmedAt() != null || m.getSafeStopCleanedAt() != null)
            throw new ApiException(ErrorCode.MISSION_SAFE_STOP_NOT_READY);
        if (m.getSafeStopStatus() != MissionSafeStopStatus.FAILED) {
            m.setSafeStopStatus(MissionSafeStopStatus.FAILED); m.setSafeStopFailureReason(reason.strip()); missions.saveAndFlush(m);
        }
        return response(m);
    }

    @Override @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public MissionSafeStopResponse cleanup(String id) {
        var m = lock(id);
        if (m.getSafeStopCleanedAt() != null) return response(m);
        acceptedHistoryGuard(m);
        requireCleanupRequested(m);
        var deviceRows = devices.findByMissionId(id); var staffRows = crew.findByMissionId(id);
        var ids = new TreeSet<String>();
        for (var a : deviceRows) ids.add(a.getDevice().getId());
        for (var a : staffRows) ids.add(a.getStaff().getId());
        resources.lockMissionResources(id, ids);
        var sessions = connections.findByMissionId(id);
        requireCleanupReady(m, sessions);
        Instant now = Instant.now();
        for (var session : sessions) if ("CONNECTED".equals(session.getConnectionStatus()) || Boolean.TRUE.equals(session.getTelemetryActive())) {
            session.setConnectionStatus("DISCONNECTED"); session.setTelemetryActive(false);
            if (session.getDisconnectedAt() == null) session.setDisconnectedAt(now);
            session.setDisconnectReason("SAFE_STOP_CONFIRMED");
        }
        connections.saveAll(sessions);
        for (var token : tokens.findByMissionId(id)) if (!token.isRevoked()) token.setRevoked(true);
        for (var a : deviceRows) if (a.getReleasedAt() == null) { a.setReleasedAt(now); a.setReleaseReason("SAFE_STOP_CLEANUP"); }
        for (var a : staffRows) if (a.getReleasedAt() == null) { a.setReleasedAt(now); a.setReleaseReason("SAFE_STOP_CLEANUP"); }
        devices.saveAll(deviceRows); crew.saveAll(staffRows); resources.release(id, "SAFE_STOP_CLEANUP");
        var owned = schedules.findByReferenceId(id);
        for (var s : owned) if (s.getScheduleType() == UserScheduleType.MISSION
                && s.getStatus() != UserScheduleStatus.CANCELLED && s.getStatus() != UserScheduleStatus.COMPLETED) {
            s.setStatus(UserScheduleStatus.CANCELLED); s.setNotes("SAFE_STOP_CLEANUP: " + (s.getNotes() == null ? "" : s.getNotes()));
        }
        schedules.saveAll(owned);
        m.setSafeStopStatus(MissionSafeStopStatus.READY_FOR_CANCELLATION); m.setSafeStopCleanedAt(now); missions.saveAndFlush(m);
        return response(m);
    }

    @Override @Transactional(readOnly=true) @PreAuthorize("@missionAuthorizationService.canViewMission(#missionId)")
    public MissionSafeStopResponse get(String missionId) {
        return response(missions.findById(missionId).orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND)));
    }
    private MissionSafeStopResponse response(Mission m) {
        boolean planningAuthority = hasAuthority("ROLE_MANAGER") || hasAuthority("ROLE_ADMIN");
        // Capability is an affordance snapshot, never authorization for a later write. Commands re-check under locks.
        // Cancelled Missions expose no actions; existing cleanup replay remains callable without changing state.
        boolean readableState = m.getOrder() != null && m.getOrder().getId() != null && m.getStatus() != null
                && m.getOrder().getOrderStatus() != null && m.getOrder().getExecutionPlanStatus() != null
                && (m.getSafeStopRequestedAt() == null || (m.getSafeStopStatus() != null && m.getSafeStopRequiresLanding() != null))
                && m.getStatus() != MissionStatus.CANCELLED && m.getCancellationAt() == null;
        boolean request = readableState && planningAuthority && allowed(() -> {
            acceptedHistoryGuard(m); requireRequestAllowed(m, m.getOrder().getExecutionPlanRevision());
        });
        boolean confirm = readableState && hasAuthority("ROLE_STAFF") && allowed(() -> {
            acceptedHistoryGuard(m); requireConfirmationAllowed(m, actor.getCurrentUser());
        });
        boolean cleanup = readableState && planningAuthority && allowed(() -> {
            if (m.getSafeStopCleanedAt() != null) return; // Existing idempotent replay.
            acceptedHistoryGuard(m); requireCleanupRequested(m); requireCleanupReady(m, connections.findByMissionId(m.getId()));
        });
        boolean cancel = readableState && planningAuthority && allowed(() -> MissionCancellationGuard.requireAllowed(
                m.getOrder().getId(), m.getOrder(), m, m.getOrder().getExecutionPlanRevision(), results, missions));
        return new MissionSafeStopResponse(m.getId(), m.getSafeStopStatus(), m.getSafeStopRequestedAt(),
                m.getSafeStopConfirmedAt(), m.getSafeStopCleanedAt(), m.getSafeStopFailureReason(), Boolean.TRUE.equals(m.getSafeStopRequiresLanding()),
                m.getSafeStopRequestedBy(), m.getSafeStopReason(), m.getSafeStopConfirmedBy(), m.getSafeStopConfirmation(),
                request, confirm, cleanup, cancel);
    }

    private boolean hasAuthority(String authority) {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)
                && authentication.getAuthorities().stream().anyMatch(granted -> authority.equals(granted.getAuthority()));
    }

    private boolean allowed(Runnable guard) {
        try { guard.run(); return true; }
        catch (ApiException rejected) {
            // Expected business denials become false. Infrastructure/internal failures remain visible.
            if (rejected.getStatus().is4xxClientError()) return false;
            throw rejected;
        }
    }
}
