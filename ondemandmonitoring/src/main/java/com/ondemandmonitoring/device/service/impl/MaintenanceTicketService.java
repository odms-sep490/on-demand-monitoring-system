package com.ondemandmonitoring.device.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.enums.DeviceStatus;
import com.ondemandmonitoring.device.repository.DeviceRepository;
import com.ondemandmonitoring.device.domain.MaintenanceTicket;
import com.ondemandmonitoring.device.dto.request.AssignMaintenanceStaffRequest;
import com.ondemandmonitoring.device.dto.request.ResolveMaintenanceTicketRequest;
import com.ondemandmonitoring.device.dto.response.MaintenanceTicketResponse;
import com.ondemandmonitoring.device.repository.MaintenanceTicketRepository;
import com.ondemandmonitoring.device.service.IMaintenanceTicketService;
import com.ondemandmonitoring.device.service.IMaintenanceAuthorizationService;
import com.ondemandmonitoring.user.service.IStaffDirectoryService;
import org.springframework.security.access.prepost.PreAuthorize;
import com.ondemandmonitoring.user.domain.User;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class MaintenanceTicketService implements IMaintenanceTicketService {

    MaintenanceTicketRepository maintenanceTicketRepository;
    DeviceRepository deviceRepository;
    IStaffDirectoryService staffDirectory;
    IMaintenanceAuthorizationService authorization;

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN','STAFF')")
    public List<MaintenanceTicketResponse> getAllTickets(String status, String deviceId, String staffId) {
        List<MaintenanceTicket> tickets = maintenanceTicketRepository.findAll();
        return tickets.stream()
                .filter(t -> authorization.canViewTicket(t.getId()))
                .filter(t -> status == null || status.isBlank() || t.getStatus().equalsIgnoreCase(status))
                .filter(t -> deviceId == null || deviceId.isBlank()
                        || (t.getDevice() != null && t.getDevice().getId().equals(deviceId)))
                .filter(t -> staffId == null || staffId.isBlank()
                        || (t.getAssignedStaff() != null
                                && t.getAssignedStaff().getId().equals(staffId)))
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("@maintenanceAuthorizationService.canViewTicket(#id)")
    public MaintenanceTicketResponse getTicketById(String id) {
        MaintenanceTicket ticket = maintenanceTicketRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Maintenance ticket not found: " + id));
        return mapToResponse(ticket);
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public MaintenanceTicketResponse assignStaff(String id, AssignMaintenanceStaffRequest request) {
        MaintenanceTicket ticket = maintenanceTicketRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Maintenance ticket not found: " + id));

        User staff = staffDirectory.requireActiveStaff(request.getStaffId());

        ticket.setAssignedStaff(staff);
        ticket.setStatus("IN_PROGRESS");
        MaintenanceTicket saved = maintenanceTicketRepository.save(ticket);
        return mapToResponse(saved);
    }

    @Override
    @Transactional
    @PreAuthorize("@maintenanceAuthorizationService.canResolveTicket(#id)")
    public MaintenanceTicketResponse resolveTicket(String id, ResolveMaintenanceTicketRequest request) {
        MaintenanceTicket ticket = maintenanceTicketRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Maintenance ticket not found: " + id));

        ticket.setResolutionNotes(request.getResolutionNotes());
        ticket.setStatus("RESOLVED");
        ticket.setResolvedAt(Instant.now());

        // Update the associated device status back to AVAILABLE (or requested status)
        Device device = ticket.getDevice();
        if (device != null) {
            DeviceStatus targetStatus = request.getNewDeviceStatus() != null ? request.getNewDeviceStatus()
                    : DeviceStatus.AVAILABLE;
            device.setStatus(targetStatus);
            deviceRepository.save(device);
        }

        MaintenanceTicket saved = maintenanceTicketRepository.save(ticket);
        return mapToResponse(saved);
    }

    private MaintenanceTicketResponse mapToResponse(MaintenanceTicket ticket) {
        return MaintenanceTicketResponse.builder()
                .id(ticket.getId())
                .ticketCode(ticket.getTicketCode())
                .deviceId(ticket.getDevice() != null ? ticket.getDevice().getId() : null)
                .deviceCode(ticket.getDevice() != null ? ticket.getDevice().getSerialNumber() : null)
                .assignedStaffId(
                        ticket.getAssignedStaff() != null ? ticket.getAssignedStaff().getId() : null)
                .assignedStaffName(
                        ticket.getAssignedStaff() != null ? ticket.getAssignedStaff().getFullName() : null)
                .reportedBy(ticket.getReportedBy())
                .issueType(ticket.getIssueType())
                .severity(ticket.getSeverity())
                .description(ticket.getDescription())
                .status(ticket.getStatus())
                .resolutionNotes(ticket.getResolutionNotes())
                .openedAt(ticket.getOpenedAt())
                .resolvedAt(ticket.getResolvedAt())
                .closedAt(ticket.getClosedAt())
                .build();
    }
}

