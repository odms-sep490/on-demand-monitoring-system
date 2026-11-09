package com.ondemandmonitoring.mission.enums;

public enum ChecklistExecutionStatus {
    PENDING, IN_PROGRESS, COMPLETED, UNABLE_TO_VERIFY;

    public boolean isTerminal() {
        return this == COMPLETED || this == UNABLE_TO_VERIFY;
    }
}
