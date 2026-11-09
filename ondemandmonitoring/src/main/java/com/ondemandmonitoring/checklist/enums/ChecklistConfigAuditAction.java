package com.ondemandmonitoring.checklist.enums;

/** What an administrator did to the checklist library or to a service's checklist template. */
public enum ChecklistConfigAuditAction {
    CHECKLIST_CREATED,
    CHECKLIST_UPDATED,
    CHECKLIST_ACTIVATED,
    CHECKLIST_DEACTIVATED,
    SERVICE_CHECKLIST_ASSIGNED,
    SERVICE_CHECKLIST_UPDATED,
    SERVICE_CHECKLIST_REMOVED,
    SERVICE_CHECKLIST_REORDERED,
    SERVICE_CHECKLIST_FORKED
}
