package com.ondemandmonitoring.order.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.finance.enums.QuoteStatus;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.repository.QuoteRepository;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.repository.MissionChecklistAssignmentRepository;
import com.ondemandmonitoring.mission.repository.MissionChecklistExecutionRepository;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.ExecutionPlanStatus;
import com.ondemandmonitoring.order.enums.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderChecklistEditPolicy {
    private final QuoteRepository quoteRepository;
    private final InvoiceRepository invoiceRepository;
    private final MissionRepository missionRepository;
    private final MissionChecklistAssignmentRepository assignmentRepository;
    private final MissionChecklistExecutionRepository executionRepository;

    public record EditCapability(boolean canEdit, String blockedReason, ErrorCode errorCode) {
        public static EditCapability allowed() {
            return new EditCapability(true, null, null);
        }

        public static EditCapability blocked(String reason, ErrorCode errorCode) {
            return new EditCapability(false, reason, errorCode);
        }

        public void assertEditable() {
            if (!canEdit) {
                throw new ApiException(errorCode != null ? errorCode : ErrorCode.ORDER_CHECKLIST_EDIT_LOCKED);
            }
        }
    }

    public EditCapability evaluate(Order order) {
        return evaluate(order, null);
    }

    public EditCapability evaluate(Order order, String actorCustomerId) {
        if (order == null) {
            return EditCapability.blocked("ORDER_NOT_FOUND", ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (actorCustomerId != null
                && order.getCustomer() != null
                && order.getCustomer().getId() != null
                && !order.getCustomer().getId().equals(actorCustomerId)) {
            return EditCapability.blocked("NOT_ORDER_OWNER", ErrorCode.ACCESS_DENIED);
        }

        OrderStatus status = order.getOrderStatus();
        if (status != null
                && status != OrderStatus.PENDING
                && status != OrderStatus.NEEDS_INFO
                && status != OrderStatus.APPROVED) {
            return EditCapability.blocked("ORDER_STATUS_" + status.name(), ErrorCode.ORDER_CHECKLIST_EDIT_LOCKED);
        }
        if (status == OrderStatus.NEEDS_INFO) {
            String scope = order.getRequestedUpdateScope();
            if (scope != null && !scope.isBlank()) {
                return EditCapability.blocked("NEEDS_INFO_SCOPE_RESTRICTED", ErrorCode.ORDER_CHECKLIST_EDIT_LOCKED);
            }
        }
        if (order.getExecutionPlanStatus() == ExecutionPlanStatus.FINALIZED || order.getExecutionPlanRevision() > 0) {
            return EditCapability.blocked("EXECUTION_STARTED", ErrorCode.ORDER_CHECKLIST_EDIT_LOCKED);
        }

        String orderId = order.getId();
        if (orderId != null) {
            if (quoteRepository != null && quoteRepository.findHistory(orderId).stream()
                    .anyMatch(q -> q.getStatus() == QuoteStatus.ACCEPTED_BY_CUSTOMER)) {
                return EditCapability.blocked("QUOTE_ACCEPTED", ErrorCode.ORDER_CHECKLIST_EDIT_LOCKED);
            }
            if (invoiceRepository != null && invoiceRepository.findByOrderId(orderId).isPresent()) {
                return EditCapability.blocked("INVOICE_ISSUED", ErrorCode.ORDER_CHECKLIST_EDIT_LOCKED);
            }
            if ((executionRepository != null && executionRepository.existsByOrderId(orderId))
                    || (assignmentRepository != null && assignmentRepository.existsByOrderIdAndRemovedAtIsNull(orderId))
                    || (missionRepository != null && missionRepository.existsByOrder_IdAndStatusNot(orderId, MissionStatus.CANCELLED))) {
                return EditCapability.blocked("EXECUTION_STARTED", ErrorCode.ORDER_CHECKLIST_EDIT_LOCKED);
            }
        }

        return EditCapability.allowed();
    }
}
