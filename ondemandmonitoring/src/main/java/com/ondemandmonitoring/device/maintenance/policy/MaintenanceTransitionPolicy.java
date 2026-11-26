package com.ondemandmonitoring.device.maintenance.policy;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.device.domain.MaintenanceTicket;
import com.ondemandmonitoring.device.maintenance.domain.MaintenanceReportVersion;
import com.ondemandmonitoring.device.maintenance.enums.MaintenanceWorkflowStage;
import com.ondemandmonitoring.device.maintenance.enums.PostCheckResult;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Centralized state machine & authorization policy for maintenance workflow transitions.
 * No controller may arbitrarily set a workflow stage; every mutation must pass through this policy.
 */
@Component
public class MaintenanceTransitionPolicy {

    public static final String ACTION_SAVE_INBOUND_DRAFT = "SAVE_INBOUND_DRAFT";
    public static final String ACTION_SUBMIT_INBOUND_HANDOVER = "SUBMIT_INBOUND_HANDOVER";
    public static final String ACTION_CONFIRM_RECEIPT = "CONFIRM_RECEIPT";
    public static final String ACTION_REJECT_RECEIPT = "REJECT_RECEIPT";
    public static final String ACTION_START_WORK = "START_WORK";
    public static final String ACTION_WAIT_PARTS = "WAIT_PARTS";
    public static final String ACTION_RESUME_WORK = "RESUME_WORK";
    public static final String ACTION_EDIT_WORK_AND_PARTS = "EDIT_WORK_AND_PARTS";
    public static final String ACTION_SAVE_REPORT_DRAFT = "SAVE_REPORT_DRAFT";
    public static final String ACTION_SUBMIT_RETURN = "SUBMIT_RETURN";
    public static final String ACTION_ACCEPT_RETURN = "ACCEPT_RETURN";
    public static final String ACTION_REQUEST_REWORK = "REQUEST_REWORK";
    public static final String ACTION_CANCEL_WORKFLOW = "CANCEL_WORKFLOW";
    public static final String ACTION_CLAIM_TICKET = "CLAIM_TICKET";
    public static final String ACTION_RELEASE_TICKET = "RELEASE_TICKET";

    /** A submitted handover nobody has claimed yet, visible to every Technical in the shared queue. */
    public boolean isInSharedQueue(MaintenanceTicket ticket) {
        return ticket.getAssignedStaff() == null
                && effectiveStage(ticket) == MaintenanceWorkflowStage.AWAITING_TECHNICAL_RECEIPT;
    }

    public MaintenanceWorkflowStage effectiveStage(MaintenanceTicket ticket) {
        if (ticket.getWorkflowStage() != null) {
            return ticket.getWorkflowStage();
        }
        if ("CLOSED".equalsIgnoreCase(ticket.getStatus())) {
            return MaintenanceWorkflowStage.COMPLETED;
        }
        return MaintenanceWorkflowStage.DRAFT_HANDOVER;
    }

    public void requireExpectedVersion(MaintenanceTicket ticket, Long expectedVersion) {
        if (expectedVersion == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "expectedVersion is required for workflow mutations");
        }
        Long actual = ticket.getVersion();
        if (!Objects.equals(actual, expectedVersion)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Maintenance ticket was modified concurrently (expected version "
                            + expectedVersion + ", actual " + actual + "). Reload and retry.");
        }
    }

    public void requireStageIn(MaintenanceTicket ticket, MaintenanceWorkflowStage... allowedStages) {
        MaintenanceWorkflowStage current = effectiveStage(ticket);
        for (MaintenanceWorkflowStage allowed : allowedStages) {
            if (current == allowed) {
                return;
            }
        }
        throw new ApiException(
                HttpStatus.CONFLICT,
                "Action is not allowed in current maintenance stage: " + current.name());
    }

    public boolean isDesignatedTechnical(User actor, MaintenanceTicket ticket) {
        return actor != null
                && ticket.getAssignedStaff() != null
                && actor.getId().equals(ticket.getAssignedStaff().getId());
    }

    public boolean isDesignatedReceivingManager(User actor, MaintenanceTicket ticket) {
        if (actor == null || actor.getRole() == null) {
            return false;
        }
        RoleCode role = actor.getRole().getCode();
        if (role != RoleCode.MANAGER && role != RoleCode.ADMIN) {
            return false;
        }
        if (ticket.getReceivingManager() == null) {
            return true;
        }
        return actor.getId().equals(ticket.getReceivingManager().getId()) || role == RoleCode.ADMIN;
    }

    public void requireDesignatedTechnical(User actor, MaintenanceTicket ticket) {
        if (!isDesignatedTechnical(actor, ticket)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "Only the designated Technical user assigned to this ticket can perform this action.");
        }
    }

    public void requireReceivingManager(User actor, MaintenanceTicket ticket) {
        if (!isDesignatedReceivingManager(actor, ticket)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "Only the designated Manager can accept return or request rework.");
        }
        // Prevent Technical from self-accepting even if roles overlap in misconfigured accounts.
        if (ticket.getAssignedStaff() != null && actor.getId().equals(ticket.getAssignedStaff().getId())) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "Assigned Technical cannot self-accept maintenance return on behalf of Manager.");
        }
    }

    public boolean canViewTicket(User actor, MaintenanceTicket ticket, boolean isCustodian) {
        if (actor == null || actor.getRole() == null) {
            return false;
        }
        RoleCode role = actor.getRole().getCode();
        if (role == RoleCode.CUSTOMER) {
            return false;
        }
        if (role == RoleCode.MANAGER || role == RoleCode.ADMIN) {
            return true;
        }
        return isDesignatedTechnical(actor, ticket) || isCustodian
                || (role == RoleCode.STAFF && isInSharedQueue(ticket));
    }

    public List<String> computeAvailableActions(
            User actor,
            MaintenanceTicket ticket,
            boolean isCustodian) {
        List<String> actions = new ArrayList<>();
        if (actor == null || actor.getRole() == null || actor.getRole().getCode() == RoleCode.CUSTOMER) {
            return actions;
        }
        MaintenanceWorkflowStage stage = effectiveStage(ticket);
        boolean isTechnical = isDesignatedTechnical(actor, ticket);
        boolean isManager = isDesignatedReceivingManager(actor, ticket)
                && (ticket.getAssignedStaff() == null || !actor.getId().equals(ticket.getAssignedStaff().getId()));

        switch (stage) {
            case DRAFT_HANDOVER -> {
                if (isCustodian) {
                    actions.add(ACTION_SAVE_INBOUND_DRAFT);
                    actions.add(ACTION_SUBMIT_INBOUND_HANDOVER);
                    actions.add(ACTION_CANCEL_WORKFLOW);
                }
            }
            case RECEIPT_REJECTED -> {
                if (isCustodian) {
                    actions.add(ACTION_SAVE_INBOUND_DRAFT);
                    actions.add(ACTION_SUBMIT_INBOUND_HANDOVER);
                    actions.add(ACTION_CANCEL_WORKFLOW);
                }
            }
            case AWAITING_TECHNICAL_RECEIPT -> {
                if (isTechnical) {
                    actions.add(ACTION_CONFIRM_RECEIPT);
                    actions.add(ACTION_REJECT_RECEIPT);
                    actions.add(ACTION_RELEASE_TICKET);
                } else if (isInSharedQueue(ticket) && actor.getRole().getCode() == RoleCode.STAFF) {
                    // Qualification is re-checked on claim; the button is offered to all staff in the queue.
                    actions.add(ACTION_CLAIM_TICKET);
                }
            }
            case RECEIVED -> {
                if (isTechnical) {
                    actions.add(ACTION_START_WORK);
                }
            }
            case IN_MAINTENANCE -> {
                if (isTechnical) {
                    actions.add(ACTION_EDIT_WORK_AND_PARTS);
                    actions.add(ACTION_SAVE_REPORT_DRAFT);
                    actions.add(ACTION_WAIT_PARTS);
                    actions.add(ACTION_SUBMIT_RETURN);
                }
            }
            case WAITING_PARTS -> {
                if (isTechnical) {
                    actions.add(ACTION_RESUME_WORK);
                    actions.add(ACTION_EDIT_WORK_AND_PARTS);
                    actions.add(ACTION_SAVE_REPORT_DRAFT);
                }
            }
            case REWORK_REQUIRED -> {
                if (isTechnical) {
                    actions.add(ACTION_START_WORK);
                    actions.add(ACTION_EDIT_WORK_AND_PARTS);
                    actions.add(ACTION_SAVE_REPORT_DRAFT);
                    actions.add(ACTION_SUBMIT_RETURN);
                }
            }
            case AWAITING_MANAGER_ACCEPTANCE -> {
                if (isManager) {
                    actions.add(ACTION_ACCEPT_RETURN);
                    actions.add(ACTION_REQUEST_REWORK);
                }
            }
            case COMPLETED, CANCELLED -> {
                // Terminal stages allow no further mutations.
            }
        }
        return actions;
    }

    /**
     * Computes deterministic blockers preventing return submission in the current round.
     */
    public List<String> computeReturnSubmissionBlockers(
            MaintenanceReportVersion latestReport,
            int completedWorkItemCount) {
        List<String> blockers = new ArrayList<>();
        if (completedWorkItemCount <= 0) {
            blockers.add("AT_LEAST_ONE_COMPLETED_WORK_ITEM_REQUIRED");
        }
        if (latestReport == null) {
            blockers.add("MAINTENANCE_REPORT_DRAFT_REQUIRED");
            return blockers;
        }
        if (latestReport.isLocked()) {
            blockers.add("NEW_REPORT_REVISION_REQUIRED_FOR_CURRENT_ROUND");
        }
        if (isBlank(latestReport.getReceivedConditionSummary())) {
            blockers.add("REPORT_RECEIVED_CONDITION_REQUIRED");
        }
        if (isBlank(latestReport.getFaultDiagnosisSummary())) {
            blockers.add("REPORT_DIAGNOSIS_SUMMARY_REQUIRED");
        }
        if (isBlank(latestReport.getWorkSummary())) {
            blockers.add("REPORT_WORK_SUMMARY_REQUIRED");
        }
        if (latestReport.getPostChecks() == null || latestReport.getPostChecks().isEmpty()) {
            blockers.add("POST_MAINTENANCE_CHECKS_REQUIRED");
        } else {
            boolean hasRequiredCheck = false;
            for (var check : latestReport.getPostChecks()) {
                if (check.isRequired()) {
                    hasRequiredCheck = true;
                    if (check.getResult() == PostCheckResult.FAIL) {
                        blockers.add("REQUIRED_POST_CHECK_FAILED:" + check.getCheckName());
                    }
                }
                if (check.getResult() == PostCheckResult.NOT_APPLICABLE
                        && isBlank(check.getNotApplicableReason())) {
                    blockers.add("POST_CHECK_NA_REASON_REQUIRED:" + check.getCheckName());
                }
                if (check.isEvidenceRequired()
                        && check.getResult() != PostCheckResult.NOT_APPLICABLE
                        && isBlankJsonArray(check.getEvidenceRefsJson())) {
                    blockers.add("POST_CHECK_EVIDENCE_REQUIRED:" + check.getCheckName());
                }
            }
            if (!hasRequiredCheck) {
                blockers.add("AT_LEAST_ONE_REQUIRED_POST_CHECK_NEEDED");
            }
        }
        return blockers;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean isBlankJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return true;
        }
        String trimmed = json.trim();
        return "[]".equals(trimmed) || "null".equals(trimmed);
    }
}
