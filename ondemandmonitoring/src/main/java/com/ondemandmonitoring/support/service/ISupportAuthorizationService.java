package com.ondemandmonitoring.support.service;

public interface ISupportAuthorizationService {
    boolean canManageTickets();
    boolean canAccessTicket(String ticketId);
    boolean canProcessTicket(String ticketId);
    boolean canViewCustomerTickets(String customerId);
}
