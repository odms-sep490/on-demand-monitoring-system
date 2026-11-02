package com.ondemandmonitoring.delivery.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.delivery.domain.*;
import com.ondemandmonitoring.delivery.dto.request.RevisionRequest;
import com.ondemandmonitoring.delivery.dto.response.*;
import com.ondemandmonitoring.delivery.enums.*;
import com.ondemandmonitoring.delivery.repository.*;
import com.ondemandmonitoring.delivery.service.IResultReviewService;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.media.service.IResultRetentionAccessService;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.support.domain.SupportCustomerTicket;
import com.ondemandmonitoring.support.enums.SupportTicketKind;
import com.ondemandmonitoring.support.repository.SupportCustomerTicketRepository;
import com.ondemandmonitoring.support.service.ISupportAuthorizationService;
import com.ondemandmonitoring.support.service.impl.SupportTicketService;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ResultReviewService implements IResultReviewService {
    private final ResultReviewRoundRepository rounds;
    private final ResultReviewRoundItemRepository roundItems;
    private final ResultReviewRoundEvidenceRepository evidence;
    private final ResultRevisionRequestRepository requests;
    private final ResultRevisionRequestItemRepository requestItems;
    private final ResultRevisionRequestItemMediaRepository requestMedia;
    private final OrderDeliveryRepository deliveries;
    private final OrderRepository orders;
    private final MissionRepository missions;
    private final SupportCustomerTicketRepository tickets;
    private final SupportTicketService supportTickets;
    private final ISupportAuthorizationService supportAuthorization;
    private final AuthenticatedUserResolver currentUser;
    private final IMediaObjectStorage storage;
    private final IResultRetentionAccessService retentionAccess;
    private final ObjectMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public ResultReviewResponse getOrderReview(String orderId, int page, int size) {
        Order order = orders.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        assertOwner(order);
        if (page < 0 || size < 1 || size > 100) throw new ApiException(ErrorCode.INVALID_REQUEST, "page must be nonnegative and size must be between 1 and 100");
        OrderDelivery delivery = deliveries.findByOrderId(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESULT_NOT_READY_FOR_REVIEW));
        ResultReviewRound round = currentRound(delivery);
        List<ResultReviewRoundItem> all = roundItems.findByRoundIdOrderByDisplayOrderAsc(round.getId());
        ResultRevisionRequest revision = requests.findByRoundId(round.getId()).orElse(null);
        List<ResultReviewRoundItem> pageItems = all.stream().skip((long) page * size).limit(size).toList();
        Map<String, List<ResultReviewRoundEvidence>> evidenceByItem = pageItems.isEmpty() ? Map.of()
                : evidence.findByRoundItemIdInOrderByRoundItemIdAscCreatedAtAsc(pageItems.stream().map(ResultReviewRoundItem::getId).toList())
                        .stream().collect(Collectors.groupingBy(e -> e.getRoundItem().getId()));
        List<ResultReviewResponse.Item> items = pageItems.stream()
                .map(item -> mapItem(item, evidenceByItem.getOrDefault(item.getId(), List.of()))).toList();
        boolean open = round.getStatus() == ResultReviewRoundStatus.OPEN && delivery.getStatus() == DeliveryStatus.CUSTOMER_REVIEW && revision == null;
        String blocked = open ? null : revision != null ? "REVISION_ALREADY_SUBMITTED" : round.getStatus().name();
        ResultReviewResponse.RevisionSummary summary = null;
        if (revision != null) {
            SupportCustomerTicket ticket = tickets.findById(revision.getTicketId()).orElse(null);
            summary = new ResultReviewResponse.RevisionSummary(revision.getId(), revision.getTicketId(), ticket == null ? null : ticket.getTicketCode());
        }
        return new ResultReviewResponse(round.getId(), round.getRoundNumber(), round.getVersion(), round.getPublishedAt(),
                round.getChecklistRevision(), open, open, blocked, summary, page, size, all.size(), items);
    }

    @Override
    @Transactional
    public ResultRevisionSubmittedResponse submit(String orderId, RevisionRequest request) {
        User customer = currentUser.getCurrentUser();
        Order order = orders.findByIdForUpdate(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        assertOwner(order);
        if (request == null || request.reviewRoundId() == null || request.expectedRoundVersion() == null
                || request.clientRequestId() == null || request.items() == null) {
            throw new ApiException(ErrorCode.RESULT_REVIEW_REFRESH_REQUIRED);
        }
        Normalized normalized = normalize(request);
        String fingerprint = fingerprint(orderId, request.reviewRoundId(), normalized);
        var replay = requests.findByCustomerIdAndClientRequestId(customer.getId(), request.clientRequestId());
        if (replay.isPresent()) {
            ResultRevisionRequest prior = replay.get();
            if (!orderId.equals(prior.getOrder().getId()) || !request.reviewRoundId().equals(prior.getRound().getId())
                    || !prior.getPayloadFingerprint().equals(fingerprint))
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            SupportCustomerTicket ticket = tickets.findById(prior.getTicketId()).orElseThrow(() -> new ApiException(ErrorCode.SUPPORT_TICKET_NOT_FOUND));
            return submitted(prior, ticket);
        }
        OrderDelivery delivery = deliveries.findByOrderIdForUpdate(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESULT_NOT_READY_FOR_REVIEW));
        ResultReviewRound round = rounds.findByIdForUpdate(delivery.getCurrentReviewRoundId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESULT_REVIEW_REFRESH_REQUIRED));
        if (!round.getId().equals(request.reviewRoundId()) || !Objects.equals(round.getVersion(), request.expectedRoundVersion()))
            throw new ApiException(ErrorCode.RESULT_REVIEW_REFRESH_REQUIRED);
        if (round.getStatus() != ResultReviewRoundStatus.OPEN || delivery.getStatus() != DeliveryStatus.CUSTOMER_REVIEW)
            throw new ApiException(ErrorCode.RESULT_REVIEW_REFRESH_REQUIRED);
        if (requests.findByRoundId(round.getId()).isPresent()) throw new ApiException(ErrorCode.RESULT_REVISION_ALREADY_SUBMITTED);

        Map<String, ResultReviewRoundItem> allowedItems = roundItems.findByRoundIdOrderByDisplayOrderAsc(round.getId()).stream()
                .collect(Collectors.toMap(ResultReviewRoundItem::getId, i -> i));
        StringBuilder description = new StringBuilder();
        if (normalized.generalNote() != null) description.append(normalized.generalNote().trim()).append("\n\n");
        description.append("Customer requested changes to ").append(normalized.items().size()).append(" checklist item(s).");
        SupportCustomerTicket ticket = supportTickets.createResultRevisionTicket(customer, orderId, description.toString().trim());

        ResultRevisionRequest saved = new ResultRevisionRequest();
        saved.setRound(round); saved.setOrder(order); saved.setCustomerId(customer.getId()); saved.setTicketId(ticket.getId());
        saved.setClientRequestId(request.clientRequestId()); saved.setPayloadFingerprint(fingerprint);
        saved.setGeneralNote(normalized.generalNote()); saved.setSubmittedAt(Instant.now());
        saved = requests.saveAndFlush(saved);
        int displayOrder = 0;
        for (NormalizedItem input : normalized.items()) {
            ResultReviewRoundItem item = allowedItems.get(input.roundItemId());
            if (item == null) throw new ApiException(ErrorCode.RESULT_REVIEW_ITEM_INVALID);
            ResultRevisionRequestItem row = new ResultRevisionRequestItem();
            row.setRequest(saved); row.setRoundItem(item); row.setReasonCode(input.reasonCode()); row.setNote(input.note()); row.setDisplayOrder(displayOrder++);
            row = requestItems.save(row);
            Set<String> uniqueEvidenceIds = new HashSet<>(input.roundEvidenceIds());
            if (uniqueEvidenceIds.size() != input.roundEvidenceIds().size()) throw new ApiException(ErrorCode.RESULT_REVIEW_ITEM_INVALID);
            List<ResultReviewRoundEvidence> validEvidence = evidence.findByRoundItemIdOrderByCreatedAtAsc(item.getId());
            Map<String, ResultReviewRoundEvidence> evidenceById = validEvidence.stream().collect(Collectors.toMap(ResultReviewRoundEvidence::getId, e -> e));
            for (String evidenceId : input.roundEvidenceIds()) {
                ResultReviewRoundEvidence selected = evidenceById.get(evidenceId);
                if (selected == null) throw new ApiException(ErrorCode.RESULT_REVIEW_ITEM_INVALID);
                ResultRevisionRequestItemMedia link = new ResultRevisionRequestItemMedia();
                link.setRequestItem(row); link.setRoundEvidence(selected); requestMedia.save(link);
            }
        }
        String firstReason = normalized.items().stream().map(i -> i.reasonCode().name() + ": " + i.note()).collect(Collectors.joining("\n"));
        delivery.setStatus(DeliveryStatus.REVISION_REQUESTED);
        delivery.setRevisionReason(truncate(firstReason, 2000));
        delivery.setRevisionRequestedAt(saved.getSubmittedAt());
        delivery.setRevisionCount(delivery.getRevisionCount() + 1);
        round.setStatus(ResultReviewRoundStatus.REVISION_REQUESTED);
        requests.flush(); requestItems.flush(); requestMedia.flush();
        return submitted(saved, ticket);
    }

    @Override
    @Transactional(readOnly = true)
    public ResultRevisionTicketReviewResponse getTicketReview(String ticketId) {
        SupportCustomerTicket ticket = tickets.findById(ticketId).orElseThrow(() -> new ApiException(ErrorCode.SUPPORT_TICKET_NOT_FOUND));
        User user = currentUser.getCurrentUser();
        boolean manager = supportAuthorization.canManageTickets();
        if (!manager && (user.getRole().getCode() != RoleCode.CUSTOMER || !user.getId().equals(ticket.getCustomerId())))
            throw new ApiException(ErrorCode.ACCESS_DENIED);
        if (ticket.getTicketKind() != SupportTicketKind.RESULT_REVISION) throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        ResultRevisionRequest request = requests.findByTicketId(ticketId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        ResultReviewRound round = request.getRound();
        List<ResultRevisionRequestItem> requestRows = requestItems.findByRequestIdOrderByDisplayOrderAsc(request.getId());
        Map<String, List<ResultReviewRoundEvidence>> evidenceByRequestItem = requestMedia.findByRequestItemIdIn(
                        requestRows.stream().map(ResultRevisionRequestItem::getId).toList()).stream()
                .collect(Collectors.groupingBy(link -> link.getRequestItem().getId(),
                        Collectors.mapping(ResultRevisionRequestItemMedia::getRoundEvidence, Collectors.toList())));
        List<ResultRevisionTicketReviewResponse.Item> items = requestRows.stream().map(row -> {
            List<ResultReviewRoundEvidence> selectedEvidence = evidenceByRequestItem.getOrDefault(row.getId(), List.of());
            List<ResultReviewResponse.Evidence> mediaRows = selectedEvidence.stream()
                    .map(e -> mapEvidence(e, manager, true)).toList();
            ResultReviewRoundItem source = row.getRoundItem();
            var guidance = source.getGuidanceSnapshot();
            return new ResultRevisionTicketReviewResponse.Item(source.getId(), source.getOrderChecklistItem().getId(), source.getContentSnapshot(),
                    text(guidance, "description"), strings(guidance, "executionSteps"), strings(guidance, "inputRequirements"),
                    strings(guidance, "expectedOutputs"), source.getMissionId(), null, row.getReasonCode().name(), row.getNote(),
                    selectedEvidence.stream().map(ResultReviewRoundEvidence::getId).toList(), mediaRows);
        }).toList();
        return new ResultRevisionTicketReviewResponse(ticket.getId(), ticket.getTicketCode(), request.getOrder().getId(), round.getId(),
                round.getRoundNumber(), round.getVersion(), round.getPublishedAt(), request.getGeneralNote(), request.getSubmittedAt(), items);
    }

    private ResultReviewResponse.Item mapItem(ResultReviewRoundItem item, List<ResultReviewRoundEvidence> evidenceRows) {
        List<ResultReviewResponse.Evidence> mediaRows = evidenceRows.stream().map(e -> mapEvidence(e, false, false)).toList();
        return new ResultReviewResponse.Item(item.getId(), item.getOrderChecklistItem().getId(), item.getContentSnapshot(),
                text(item.getGuidanceSnapshot(), "description"), strings(item.getGuidanceSnapshot(), "executionSteps"),
                strings(item.getGuidanceSnapshot(), "inputRequirements"), strings(item.getGuidanceSnapshot(), "expectedOutputs"),
                item.getMissionId(), null, mediaRows);
    }

    private ResultReviewResponse.Evidence mapEvidence(ResultReviewRoundEvidence row, boolean downloadable, boolean ticketContext) {
        var asset = row.getMediaAsset();
        try {
            retentionAccess.requireAccessible(asset);
        } catch (ApiException ex) {
            if (ex.getErrorCode() != ErrorCode.RESULT_RETENTION_EXPIRED && ex.getErrorCode() != ErrorCode.MEDIA_NOT_FOUND) throw ex;
            return new ResultReviewResponse.Evidence(row.getId(), asset.getId(), row.getMediaTypeSnapshot(), row.getFileNameSnapshot(),
                    asset.getFileSize(), row.getCapturedAtSnapshot(), null, null, false,
                    ex.getErrorCode() == ErrorCode.RESULT_RETENTION_EXPIRED ? "RETENTION_EXPIRED" : "MEDIA_UNAVAILABLE");
        }
        Instant expires = Instant.now().plusSeconds(storage.presignedUrlExpiresSeconds());
        return new ResultReviewResponse.Evidence(row.getId(), asset.getId(), row.getMediaTypeSnapshot(), row.getFileNameSnapshot(),
                asset.getFileSize(), row.getCapturedAtSnapshot(), storage.createPresignedGetUrl(asset.getS3Bucket(), asset.getS3Key()), expires, downloadable, null);
    }

    private String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
        return node == null || node.get(field) == null || node.get(field).isNull() ? null : node.get(field).asText();
    }
    private List<String> strings(com.fasterxml.jackson.databind.JsonNode node, String field) {
        if (node == null || !node.path(field).isArray()) return List.of();
        List<String> result = new ArrayList<>();
        node.path(field).forEach(value -> result.add(value.asText()));
        return result;
    }
    private ResultReviewRound currentRound(OrderDelivery delivery) {
        if (delivery.getCurrentReviewRoundId() == null) throw new ApiException(ErrorCode.REVIEW_ROUND_NOT_PUBLISHED);
        return rounds.findById(delivery.getCurrentReviewRoundId()).orElseThrow(() -> new ApiException(ErrorCode.RESULT_NOT_READY_FOR_REVIEW));
    }
    private void assertOwner(Order order) {
        if (!order.getCustomer().getId().equals(currentUser.getCurrentUserId())) throw new ApiException(ErrorCode.ACCESS_DENIED);
    }
    private ResultRevisionSubmittedResponse submitted(ResultRevisionRequest request, SupportCustomerTicket ticket) {
        return new ResultRevisionSubmittedResponse(request.getId(), ticket.getId(), ticket.getTicketCode(), request.getRound().getId(), request.getSubmittedAt(), DeliveryStatus.REVISION_REQUESTED);
    }
    private Normalized normalize(RevisionRequest request) {
        List<RevisionRequest.Item> values = request.items();
        if (values.isEmpty() || values.size() > 100) throw new ApiException(ErrorCode.VALIDATION_ERROR, "Submit between 1 and 100 checklist items");
        if (request.generalNote() != null && request.generalNote().length() > 2000) throw new ApiException(ErrorCode.VALIDATION_ERROR);
        Set<String> ids = new HashSet<>(); Set<String> allEvidence = new HashSet<>();
        List<NormalizedItem> items = new ArrayList<>();
        for (RevisionRequest.Item value : values) {
            if (value == null || value.roundItemId() == null || !ids.add(value.roundItemId()) || value.note() == null || value.note().isBlank() || value.note().length() > 2000)
                throw new ApiException(ErrorCode.VALIDATION_ERROR);
            ResultRevisionReasonCode code;
            try { code = ResultRevisionReasonCode.valueOf(value.reasonCode()); } catch (Exception ex) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
            List<String> evidenceIds = value.roundEvidenceIds() == null ? List.of() : value.roundEvidenceIds();
            if (evidenceIds.size() > 50 || evidenceIds.stream().anyMatch(Objects::isNull)) throw new ApiException(ErrorCode.VALIDATION_ERROR);
            allEvidence.addAll(evidenceIds);
            items.add(new NormalizedItem(value.roundItemId(), code, value.note().trim(), evidenceIds.stream().sorted().toList()));
        }
        if (allEvidence.size() > 200) throw new ApiException(ErrorCode.VALIDATION_ERROR);
        items.sort(Comparator.comparing(NormalizedItem::roundItemId));
        return new Normalized(request.generalNote() == null || request.generalNote().isBlank() ? null : request.generalNote().trim(), items);
    }
    private String fingerprint(String orderId, String reviewRoundId, Normalized value) {
        try {
            String canonical = mapper.writeValueAsString(Map.of("orderId", orderId, "reviewRoundId", reviewRoundId, "payload", value));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    private String truncate(String value, int length) { return value.length() <= length ? value : value.substring(0, length); }
    private record Normalized(String generalNote, List<NormalizedItem> items) {}
    private record NormalizedItem(String roundItemId, ResultRevisionReasonCode reasonCode, String note, List<String> roundEvidenceIds) {}
}
