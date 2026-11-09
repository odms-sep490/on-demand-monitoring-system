package com.ondemandmonitoring.device.service;

public interface IMaintenanceAuthorizationService {
    boolean canManageTickets();
    boolean canViewTicket(String id);
    boolean canResolveTicket(String id);
}
