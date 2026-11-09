package com.ondemandmonitoring.order.service;

public interface IOrderAuthorizationService {
    boolean canViewOrder(String orderId);
}
