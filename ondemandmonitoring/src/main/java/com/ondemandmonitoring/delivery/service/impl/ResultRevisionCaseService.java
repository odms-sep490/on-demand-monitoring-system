package com.ondemandmonitoring.delivery.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.delivery.domain.ResultRevisionCase;
import com.ondemandmonitoring.delivery.domain.ResultRevisionCaseItem;
import com.ondemandmonitoring.delivery.domain.ResultRevisionRequestItem;
import com.ondemandmonitoring.delivery.dto.response.ResultReviewResponse;
import com.ondemandmonitoring.delivery.dto.request.NotifyOutOfScopeRequest;
import com.ondemandmonitoring.delivery.dto.request.ResultRevisionDecisionRequest;
import com.ondemandmonitoring.delivery.dto.response.ResultRevisionCaseResponse;
import com.ondemandmonitoring.delivery.enums.ResultRevisionCaseItemStatus;
import com.ondemandmonitoring.delivery.enums.ResultRevisionCaseStatus;
import com.ondemandmonitoring.delivery.enums.ResultRevisionScope;
import com.ondemandmonitoring.delivery.enums.ResultRevisionStrategy;
import com.ondemandmonitoring.delivery.repository.ResultRevisionCaseItemRepository;
import com.ondemandmonitoring.delivery.repository.ResultRevisionCaseRepository;
import com.ondemandmonitoring.delivery.repository.ResultRevisionRequestItemRepository;
import com.ondemandmonitoring.delivery.repository.ResultRevisionRequestItemMediaRepository;
import com.ondemandmonitoring.delivery.mapper.ResultReviewEvidenceMapper;
import com.ondemandmonitoring.delivery.service.IResultRevisionCaseService;
import com.ondemandmonitoring.support.domain.SupportCustomerTicket;
import com.ondemandmonitoring.support.repository.SupportCustomerTicketRepository;
import com.ondemandmonitoring.support.service.impl.SupportTicketService;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ResultRevisionCaseService implements IResultRevisionCaseService {
    private final ResultRevisionCaseRepository cases;
    private final ResultRevisionCaseItemRepository caseItems;
    private final ResultRevisionRequestItemRepository requestItems;
    private final SupportCustomerTicketRepository tickets;
    private final SupportTicketService supportTickets;
    private final AuthenticatedUserResolver currentUser;
    private final com.ondemandmonitoring.mission.service.IMissionService missions;
    private final com.ondemandmonitoring.mission.repository.MissionRepository missionRepository;
    private final com.ondemandmonitoring.order.repository.OrderRepository orders;
    private final com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService deliveryWorkflow;
    private final ResultRevisionRequestItemMediaRepository requestMedia;
    private final ResultReviewEvidenceMapper evidenceMapper;

    @Override
    @Transactional(readOnly = true)
    public ResultRevisionCaseResponse getByOrderId(String orderId) {
        ResultRevisionCase revisionCase = cases.findByOrderIdAndActiveTrue(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        return map(revisionCase);
    }

    @Override
    @Transactional
    public ResultRevisionCaseResponse decide(String caseId, String caseItemId, ResultRevisionDecisionRequest request) {
        ResultRevisionCase revisionCase = lockCase(caseId);
        if (!revisionCase.isActive() || revisionCase.getStatus() != ResultRevisionCaseStatus.OPEN)
            throw new ApiException(ErrorCode.INVALID_DELIVERY_STATE);
        ResultRevisionCaseItem item = requireCaseItem(caseId, caseItemId, true);
        if (!Objects.equals(item.getVersion(), request.expectedVersion()))
            throw new ApiException(ErrorCode.RESULT_REVIEW_REFRESH_REQUIRED);
        if (item.getStatus() == ResultRevisionCaseItemStatus.OUT_OF_SCOPE_RESPONDED
                || item.getStatus() == ResultRevisionCaseItemStatus.OUT_OF_SCOPE_ACKNOWLEDGED)
            throw new ApiException(ErrorCode.INVALID_DELIVERY_STATE,
                    "The customer has already received the out-of-scope decision");
        if (item.getAssignedReworkMissionId() != null) {
            var assignedMission = missionRepository.findById(item.getAssignedReworkMissionId()).orElse(null);
            if (assignedMission != null && !java.util.Set.of(
                    com.ondemandmonitoring.mission.enums.MissionStatus.FAILED,
                    com.ondemandmonitoring.mission.enums.MissionStatus.CANCELLED).contains(assignedMission.getStatus()))
                throw new ApiException(ErrorCode.INVALID_DELIVERY_STATE,
                        "The checklist scope cannot change while its rework mission is active or awaiting customer review");
        }
        if (request.scopeDecision() == ResultRevisionScope.PENDING)
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "A scope decision is required");
        if (request.scopeDecision() == ResultRevisionScope.IN_SCOPE
                && request.strategy() == ResultRevisionStrategy.UNDECIDED)
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Choose reserve media or rework for an in-scope item");
        if (request.scopeDecision() == ResultRevisionScope.OUT_OF_SCOPE
                && request.strategy() != ResultRevisionStrategy.UNDECIDED)
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Out-of-scope items cannot be assigned a remediation strategy");

        String reason = request.reason().trim();
        String instructions = request.managerInstructions() == null || request.managerInstructions().isBlank()
                ? null : request.managerInstructions().trim();

        item.setScopeDecision(request.scopeDecision());
        item.setStrategy(request.strategy());
        item.setScopeReason(reason);
        item.setManagerInstructions(instructions);
        item.setScopeDecidedBy(currentUser.getCurrentUserId());
        item.setScopeDecidedAt(Instant.now());
        item.setOutOfScopeNotificationKey(null);
        item.setStatus(request.scopeDecision() == ResultRevisionScope.IN_SCOPE
                ? ResultRevisionCaseItemStatus.IN_PROGRESS : ResultRevisionCaseItemStatus.NEEDS_TRIAGE);
        caseItems.saveAndFlush(item);
        return map(revisionCase);
    }

    @Override
    @Transactional
    public ResultRevisionCaseResponse notifyOutOfScope(String caseId, String caseItemId, NotifyOutOfScopeRequest request) {
        ResultRevisionCase revisionCase = lockCase(caseId);
        ResultRevisionCaseItem item = requireCaseItem(caseId, caseItemId, true);
        if (item.getScopeDecision() != ResultRevisionScope.OUT_OF_SCOPE || item.getScopeReason() == null)
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Decide the item is out of scope and provide an explanation first");
        if (Objects.equals(item.getOutOfScopeNotificationKey(), request.clientRequestId()))
            return map(revisionCase);
        if (!Objects.equals(item.getVersion(), request.expectedVersion()))
            throw new ApiException(ErrorCode.RESULT_REVIEW_REFRESH_REQUIRED);

        supportTickets.markResultRevisionAwaitingCustomer(revisionCase.getTicketId());
        item.setOutOfScopeNotificationKey(request.clientRequestId());
        item.setStatus(ResultRevisionCaseItemStatus.OUT_OF_SCOPE_RESPONDED);
        caseItems.saveAndFlush(item);
        return map(revisionCase);
    }

    @Override
    @Transactional
    public ResultRevisionCaseResponse acknowledgeOutOfScope(String orderId, String caseItemId) {
        orders.findByIdForUpdate(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        ResultRevisionCase revisionCase = cases.findByOrderIdAndActiveTrueForUpdate(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!revisionCase.getOrder().getCustomer().getId().equals(currentUser.getCurrentUserId()))
            throw new ApiException(ErrorCode.ACCESS_DENIED);
        ResultRevisionCaseItem item = requireCaseItem(revisionCase.getId(), caseItemId, true);
        if (item.getScopeDecision() != ResultRevisionScope.OUT_OF_SCOPE)
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Only an out-of-scope decision can be acknowledged");
        if (item.getStatus() == ResultRevisionCaseItemStatus.OUT_OF_SCOPE_ACKNOWLEDGED)
            return map(revisionCase);
        if (item.getStatus() != ResultRevisionCaseItemStatus.OUT_OF_SCOPE_RESPONDED)
            throw new ApiException(ErrorCode.INVALID_DELIVERY_STATE,
                    "The Manager must explain the out-of-scope decision before acknowledgement");
        item.setStatus(ResultRevisionCaseItemStatus.OUT_OF_SCOPE_ACKNOWLEDGED);
        item.setOutOfScopeAcknowledgedBy(currentUser.getCurrentUserId());
        item.setOutOfScopeAcknowledgedAt(Instant.now());
        caseItems.saveAndFlush(item);
        closeIfResolved(revisionCase);
        deliveryWorkflow.completeAcceptedRevisionCase(orderId);
        return map(revisionCase);
    }

    private void closeIfResolved(ResultRevisionCase revisionCase) {
        List<ResultRevisionCaseItem> all = caseItems
                .findByResultRevisionCaseIdOrderByCreatedAtAsc(revisionCase.getId());
        boolean finished = !all.isEmpty() && all.stream().allMatch(item ->
                item.getStatus() == ResultRevisionCaseItemStatus.RESOLVED
                        || item.getStatus() == ResultRevisionCaseItemStatus.OUT_OF_SCOPE_ACKNOWLEDGED);
        if (finished) {
            revisionCase.close(Instant.now());
            supportTickets.resolveResultRevisionTicket(revisionCase.getTicketId(), null);
            deliveryWorkflow.completeAcceptedRevisionCase(revisionCase.getOrder().getId());
        }
    }

    private ResultRevisionCase lockCase(String caseId) {
        ResultRevisionCase reference = cases.findById(caseId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        orders.findByIdForUpdate(reference.getOrder().getId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        return cases.findByIdForUpdate(caseId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Override
    @Transactional
    public com.ondemandmonitoring.delivery.dto.response.ResultRevisionReworkMissionResponse createReworkMission(
            String caseId, com.ondemandmonitoring.mission.dto.request.CreateResultRevisionReworkMissionRequest request) {
        var mission = missions.createResultRevisionReworkMission(caseId, request);
        return new com.ondemandmonitoring.delivery.dto.response.ResultRevisionReworkMissionResponse(
                caseId, mission.getId(), mission.getMissionCode(), mission.getStatus(),
                request.caseItemIds().size());
    }

    private ResultRevisionCaseItem requireCaseItem(String caseId, String caseItemId, boolean lock) {
        ResultRevisionCaseItem item = lock
                ? caseItems.findByIdForUpdate(caseItemId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND))
                : caseItems.findById(caseItemId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!caseId.equals(item.getResultRevisionCase().getId()))
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        return item;
    }

    private ResultRevisionCaseResponse map(ResultRevisionCase revisionCase) {
        SupportCustomerTicket ticket = tickets.findById(revisionCase.getTicketId())
                .orElseThrow(() -> new ApiException(ErrorCode.SUPPORT_TICKET_NOT_FOUND));
        List<ResultRevisionCaseItem> caseItemRows = caseItems.findByResultRevisionCaseIdOrderByCreatedAtAsc(revisionCase.getId());
        var reworkMissions = missionRepository.findAllById(caseItemRows.stream().map(ResultRevisionCaseItem::getAssignedReworkMissionId)
                .filter(Objects::nonNull).distinct().toList()).stream()
                .collect(java.util.stream.Collectors.toMap(com.ondemandmonitoring.mission.domain.Mission::getId, row -> row));
        var feedbackByItem = caseItemRows.isEmpty()
                ? Map.<String, List<ResultRevisionRequestItem>>of()
                : requestItems.findByCaseItemIdInOrderByCreatedAtAscIdAsc(
                        caseItemRows.stream().map(ResultRevisionCaseItem::getId).toList()).stream()
                    .collect(Collectors.groupingBy(row -> row.getCaseItem().getId()));
        var feedbackIds = feedbackByItem.values().stream().flatMap(List::stream)
                .map(ResultRevisionRequestItem::getId).sorted().toList();
        var evidenceByFeedback = feedbackIds.isEmpty()
                ? Map.<String, List<ResultReviewResponse.Evidence>>of()
                : requestMedia.findByRequestItemIdIn(feedbackIds).stream()
                    .sorted(Comparator.comparing(link -> link.getRoundEvidence().getId()))
                    .collect(Collectors.groupingBy(link -> link.getRequestItem().getId(),
                            Collectors.mapping(link -> evidenceMapper.toResponse(link.getRoundEvidence()), Collectors.toList())));
        List<ResultRevisionCaseResponse.Item> items = caseItemRows.stream()
                .map(item -> {
                    List<ResultRevisionCaseResponse.Feedback> feedback = feedbackByItem.getOrDefault(item.getId(), List.of()).stream()
                            .map(row -> new ResultRevisionCaseResponse.Feedback(row.getRequest().getId(),
                                    row.getRequest().getRound().getRoundNumber(), row.getReasonCode(), row.getNote(),
                                    row.getRequest().getSubmittedAt(), evidenceByFeedback.getOrDefault(row.getId(), List.of())))
                            .toList();
                    return new ResultRevisionCaseResponse.Item(item.getId(), item.getOrderChecklistItem().getId(),
                            item.getOrderChecklistItem().getContent(), item.getScopeDecision(), item.getStrategy(),
                            item.getStatus(), item.getScopeReason(), item.getManagerInstructions(),
                            item.getAssignedReworkMissionId(),
                            reworkMissions.containsKey(item.getAssignedReworkMissionId())
                                    ? reworkMissions.get(item.getAssignedReworkMissionId()).getStatus().name() : null,
                            item.getVersion(), feedback);
                }).toList();
        return new ResultRevisionCaseResponse(revisionCase.getId(), revisionCase.getOrder().getId(),
                revisionCase.getTicketId(), ticket.getTicketCode(), revisionCase.getStatus(), revisionCase.isActive(),
                revisionCase.getVersion(), revisionCase.getOpenedAt(), items);
    }
}
