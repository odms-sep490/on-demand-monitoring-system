package com.ondemandmonitoring.devicecheck.service;

public interface IDeviceCheckAuthorizationService {
    boolean canViewPreCheck(String id);
    boolean canInspectPreCheck(String id);
    boolean canViewPostCheck(String id);
    boolean canInspectPostCheck(String id);
}
