package com.ondemandmonitoring.support.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.support.domain.SupportCustomerMessage;
import com.ondemandmonitoring.support.domain.SupportCustomerTicket;
import com.ondemandmonitoring.support.domain.SupportFaqArticle;
import com.ondemandmonitoring.support.domain.SupportFaqFeedback;
import com.ondemandmonitoring.support.dto.*;
import com.ondemandmonitoring.support.repository.SupportCustomerMessageRepository;
import com.ondemandmonitoring.support.repository.SupportCustomerTicketRepository;
import com.ondemandmonitoring.support.repository.SupportFaqArticleRepository;
import com.ondemandmonitoring.support.repository.SupportFaqFeedbackRepository;
import com.ondemandmonitoring.support.service.ISupportTicketService;
import com.ondemandmonitoring.support.service.ISupportAuthorizationService;
import com.ondemandmonitoring.support.enums.SupportTicketKind;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.ondemandmonitoring.user.service.IStaffDirectoryService;
import com.ondemandmonitoring.user.domain.User;
import org.springframework.security.access.prepost.PreAuthorize;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Spring Service implementation of {@link ISupportTicketService}.
 * Manages core business logic for customer support requests, conversation history,
 * operational context binding, and status progression.
 */
@Service
@RequiredArgsConstructor
public class SupportTicketService implements ISupportTicketService {

    private final SupportCustomerTicketRepository ticketRepository;
    private final SupportCustomerMessageRepository messageRepository;
    private final SupportFaqFeedbackRepository faqFeedbackRepository;
    private final SupportFaqArticleRepository faqArticleRepository;
    private final AuthenticatedUserResolver currentUser;
    private final ISupportAuthorizationService authorization;
    private final IStaffDirectoryService staffDirectory;
    private final com.ondemandmonitoring.delivery.repository.ResultRevisionCaseRepository revisionCases;

    @Override
    @Transactional
    @PreAuthorize("hasRole('CUSTOMER')")
    public SupportTicketDto createTicket(CreateSupportTicketRequest req) {
        User customer = currentUser.getCurrentUser();
        // Random, high-entropy code generation is safe across concurrent app
        // instances. The database unique constraint remains the final guard.
        String ticketCode = generateTicketCode();

        SupportCustomerTicket ticket = new SupportCustomerTicket();
        ticket.setTicketCode(ticketCode);
        ticket.setCustomerId(customer.getId());
        ticket.setCustomerName(customer.getFullName());
        ticket.setOrderId(req.getOrderId());
        ticket.setMissionId(req.getMissionId());
        ticket.setCategory(req.getCategory() != null ? req.getCategory() : "ORDERS");
        ticket.setSubject(req.getSubject());
        ticket.setDescription(req.getDescription());
        ticket.setPriority(req.getPriority() != null ? req.getPriority() : "NORMAL");
        ticket.setStatus("OPEN");
        ticket.setOpenedAt(Instant.now());

        SupportCustomerTicket saved = ticketRepository.save(ticket);

        // Initial message thread entry from customer if description is provided
        if (req.getDescription() != null && !req.getDescription().isBlank()) {
            SupportCustomerMessage initialMsg = new SupportCustomerMessage();
            initialMsg.setTicketId(saved.getId());
            initialMsg.setSenderId(saved.getCustomerId());
            initialMsg.setSenderName(saved.getCustomerName());
            initialMsg.setSenderRole("CUSTOMER");
            initialMsg.setContent(req.getDescription());
            initialMsg.setAttachmentUrl(req.getAttachmentUrl());
            initialMsg.setCreatedAt(Instant.now());
            messageRepository.save(initialMsg);
        }

        return mapToDto(saved);
    }

    /** Creates a structured result-review ticket within the caller's REQUIRED transaction. */
    @Transactional
    public SupportCustomerTicket createResultRevisionTicket(User customer, String orderId, String description) {
        String ticketCode = generateTicketCode();
        SupportCustomerTicket ticket = new SupportCustomerTicket();
        ticket.setTicketCode(ticketCode);
        ticket.setCustomerId(customer.getId());
        ticket.setCustomerName(customer.getFullName());
        ticket.setOrderId(orderId);
        ticket.setMissionId(null);
        ticket.setCategory("RESULTS");
        ticket.setTicketKind(SupportTicketKind.RESULT_REVISION);
        ticket.setSubject("Yêu cầu chỉnh sửa kết quả");
        ticket.setDescription(description);
        ticket.setPriority("NORMAL");
        ticket.setStatus("OPEN");
        ticket.setOpenedAt(Instant.now());
        SupportCustomerTicket saved = ticketRepository.save(ticket);
        return saved;
    }

    /** Checklist decisions hold the explanation; only the ticket status changes here. */
    @Transactional
    public void markResultRevisionAwaitingCustomer(String ticketId) {
        SupportCustomerTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ApiException(ErrorCode.SUPPORT_TICKET_NOT_FOUND));
        if (ticket.getTicketKind() != SupportTicketKind.RESULT_REVISION)
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        if ("OPEN".equals(ticket.getStatus()) || "ASSIGNED".equals(ticket.getStatus())) {
            ticket.setStatus("WAITING_FOR_CUSTOMER");
            ticketRepository.save(ticket);
        }
    }

    /** Marks the support conversation resolved after every checklist item in its case is settled. */
    @Transactional
    public void resolveResultRevisionTicket(String ticketId, String resolutionNotes) {
        SupportCustomerTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ApiException(ErrorCode.SUPPORT_TICKET_NOT_FOUND));
        if (ticket.getTicketKind() != SupportTicketKind.RESULT_REVISION)
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        if ("RESOLVED".equalsIgnoreCase(ticket.getStatus()) || "CLOSED".equalsIgnoreCase(ticket.getStatus())) return;
        ticket.setStatus("RESOLVED");
        ticket.setResolvedAt(Instant.now());
        ticket.setResolutionNotes(resolutionNotes);
        ticketRepository.save(ticket);
    }

    @Override
    @PreAuthorize("@supportAuthorizationService.canViewCustomerTickets(#customerId)")
    public List<SupportTicketDto> getCustomerTickets(String customerId) {
        List<SupportCustomerTicket> tickets = (customerId != null && !customerId.isBlank())
                ? ticketRepository.findByCustomerIdOrderByOpenedAtDesc(customerId)
                : ticketRepository.findAllByOrderByOpenedAtDesc();
        return tickets.stream().map(this::mapToDto).toList();
    }

    @Override
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN','STAFF')")
    public List<SupportTicketDto> getAllTickets(String statusFilter) {
        List<SupportCustomerTicket> tickets = (statusFilter != null && !statusFilter.isBlank() && !"ALL".equalsIgnoreCase(statusFilter))
                ? ticketRepository.findByStatusOrderByOpenedAtDesc(statusFilter)
                : ticketRepository.findAllByOrderByOpenedAtDesc();
        return tickets.stream().filter(ticket -> authorization.canAccessTicket(ticket.getId()))
                .map(this::mapToDto).toList();
    }

    @Override
    @PreAuthorize("@supportAuthorizationService.canAccessTicket(#id)")
    public SupportTicketDto getTicketById(String id) {
        SupportCustomerTicket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.SUPPORT_TICKET_NOT_FOUND, "Support ticket not found with ID: " + id));
        return mapToDto(ticket);
    }

    @Override
    @Transactional
    @PreAuthorize("@supportAuthorizationService.canAccessTicket(#ticketId)")
    public SupportMessageDto addMessage(String ticketId, AddMessageRequest req) {
        SupportCustomerTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ApiException(ErrorCode.SUPPORT_TICKET_NOT_FOUND, "Support ticket not found with ID: " + ticketId));

        if (ticket.getTicketKind() == SupportTicketKind.RESULT_REVISION)
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Result revision feedback must be submitted through checklist review");

        SupportCustomerMessage msg = new SupportCustomerMessage();
        msg.setTicketId(ticketId);
        User sender = currentUser.getCurrentUser();
        msg.setSenderId(sender.getId());
        msg.setSenderName(sender.getFullName());
        msg.setSenderRole(sender.getRole().getCode().name());
        msg.setContent(req.getContent());
        msg.setAttachmentUrl(req.getAttachmentUrl());
        msg.setCreatedAt(Instant.now());

        SupportCustomerMessage saved = messageRepository.save(msg);

        // Update ticket status automatically based on sender role
        if (!"CUSTOMER".equals(msg.getSenderRole())) {
            ticket.setStatus("WAITING_FOR_CUSTOMER");
        } else {
            ticket.setStatus("WAITING_FOR_STAFF");
        }
        ticketRepository.save(ticket);

        return mapToMessageDto(saved);
    }

    @Override
    @Transactional
    @PreAuthorize("@supportAuthorizationService.canProcessTicket(#ticketId)")
    public SupportTicketDto updateTicketStatus(String ticketId, UpdateTicketStatusRequest req) {
        SupportCustomerTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ApiException(ErrorCode.SUPPORT_TICKET_NOT_FOUND, "Support ticket not found with ID: " + ticketId));

        if (req.getStatus() != null && !req.getStatus().isBlank()) {
            if (ticket.getTicketKind() == SupportTicketKind.RESULT_REVISION
                    && ("RESOLVED".equalsIgnoreCase(req.getStatus()) || "CLOSED".equalsIgnoreCase(req.getStatus()))
                    && revisionCases.findByTicketId(ticketId)
                            .map(com.ondemandmonitoring.delivery.domain.ResultRevisionCase::isActive).orElse(false)) {
                throw new ApiException(ErrorCode.INVALID_DELIVERY_STATE,
                        "A result revision ticket can only be resolved after every checklist item is settled");
            }
            ticket.setStatus(req.getStatus());
            if ("RESOLVED".equalsIgnoreCase(req.getStatus()) || "CLOSED".equalsIgnoreCase(req.getStatus())) {
                ticket.setResolvedAt(Instant.now());
            }
        }
        if (req.getAssignedStaffId() != null) {
            if (!authorization.canManageTickets()) {
                throw new ApiException(ErrorCode.ACCESS_DENIED, "Only a Manager or Admin can assign support tickets");
            }
            User staff = staffDirectory.requireActiveStaff(req.getAssignedStaffId());
            ticket.setAssignedStaffId(staff.getId());
            ticket.setAssignedStaffName(staff.getFullName());
        }
        if (req.getPriority() != null) {
            ticket.setPriority(req.getPriority());
        }
        if (req.getResolutionNotes() != null) {
            ticket.setResolutionNotes(req.getResolutionNotes());
        }

        SupportCustomerTicket updated = ticketRepository.save(ticket);
        return mapToDto(updated);
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('CUSTOMER')")
    public void recordFaqFeedback(FaqFeedbackRequest req) {
        if (req.getArticleId() == null || req.getArticleId().isBlank()) return;

        SupportFaqFeedback feedback = new SupportFaqFeedback();
        feedback.setArticleId(req.getArticleId());
        feedback.setArticleQuestion(req.getArticleQuestion());
        feedback.setArticleCategory(req.getArticleCategory());
        feedback.setCustomerId(currentUser.getCurrentUserId());
        feedback.setIsHelpful(req.getIsHelpful() != null ? req.getIsHelpful() : true);
        feedback.setCreatedAt(Instant.now());

        faqFeedbackRepository.save(feedback);
    }

    @Override
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public SupportAnalyticsDto getSupportAnalytics() {
        long totalHelpfulVotes = faqFeedbackRepository.countByIsHelpfulTrue();
        List<SupportCustomerTicket> allTickets = ticketRepository.findAllByOrderByOpenedAtDesc();
        long totalTickets = allTickets.size();

        double deflectionRate = 0.0;
        if (totalHelpfulVotes + totalTickets > 0) {
            deflectionRate = Math.round(((double) totalHelpfulVotes / (totalHelpfulVotes + totalTickets)) * 1000.0) / 10.0;
        }

        // Top FAQ Articles from DB
        List<SupportFaqFeedbackRepository.TopFaqStatProjection> topProjections = faqFeedbackRepository.findTopHelpfulArticles();
        List<SupportAnalyticsDto.TopFaqDto> topFaqs = topProjections.stream()
                .limit(3)
                .map(p -> SupportAnalyticsDto.TopFaqDto.builder()
                        .articleId(p.getArticleId())
                        .articleQuestion(p.getArticleQuestion() != null ? p.getArticleQuestion() : p.getArticleId())
                        .votesCount(p.getVoteCount() != null ? p.getVoteCount() : 0)
                        .build())
                .toList();

        // Response times & SLA
        long handledCount = 0;
        double totalResponseMins = 0;
        long slaMetCount = 0;

        for (SupportCustomerTicket t : allTickets) {
            Double responseMins = null;
            List<SupportCustomerMessage> msgs = messageRepository.findByTicketIdOrderByCreatedAtAsc(t.getId());
            SupportCustomerMessage firstStaff = msgs.stream()
                    .filter(m -> "STAFF".equalsIgnoreCase(m.getSenderRole()) || "MANAGER".equalsIgnoreCase(m.getSenderRole()))
                    .findFirst()
                    .orElse(null);

            if (firstStaff != null && firstStaff.getCreatedAt() != null && t.getOpenedAt() != null) {
                long millis = firstStaff.getCreatedAt().toEpochMilli() - t.getOpenedAt().toEpochMilli();
                if (millis >= 0) {
                    responseMins = millis / 60000.0;
                }
            } else if (t.getResolvedAt() != null && t.getOpenedAt() != null) {
                long millis = t.getResolvedAt().toEpochMilli() - t.getOpenedAt().toEpochMilli();
                if (millis >= 0) {
                    responseMins = millis / 60000.0;
                }
            }

            if (responseMins != null) {
                handledCount++;
                totalResponseMins += responseMins;
                if (responseMins <= 30.0) {
                    slaMetCount++;
                }
            }
        }

        double avgResponseTimeMins = handledCount > 0 ? Math.round((totalResponseMins / handledCount) * 10.0) / 10.0 : 0.0;
        double slaComplianceRate = handledCount > 0 ? Math.round(((double) slaMetCount / handledCount) * 1000.0) / 10.0 : 100.0;

        // Category breakdown
        long ordersCount = allTickets.stream().filter(t -> "ORDERS".equalsIgnoreCase(t.getCategory())).count();
        long missionsCount = allTickets.stream().filter(t -> "MISSIONS".equalsIgnoreCase(t.getCategory()) || "SCHEDULING".equalsIgnoreCase(t.getCategory())).count();

        double ordersPct = totalTickets > 0 ? Math.round(((double) ordersCount / totalTickets) * 100.0) : 0.0;
        double missionsPct = totalTickets > 0 ? Math.round(((double) missionsCount / totalTickets) * 100.0) : 0.0;
        double mediaPct = totalTickets > 0 ? Math.max(0.0, 100.0 - ordersPct - missionsPct) : 0.0;

        Map<String, Double> categoryBreakdown = Map.of(
                "ORDERS", ordersPct,
                "MISSIONS", missionsPct,
                "MEDIA", mediaPct
        );

        return SupportAnalyticsDto.builder()
                .totalHelpfulVotes(totalHelpfulVotes)
                .totalTickets(totalTickets)
                .deflectionRate(deflectionRate)
                .topHelpfulArticles(topFaqs)
                .avgResponseTimeMins(avgResponseTimeMins)
                .slaComplianceRate(slaComplianceRate)
                .categoryBreakdown(categoryBreakdown)
                .build();
    }

    @Override
    public List<FaqArticleDto> getFaqArticles(String category, String searchQuery) {
        List<SupportFaqArticle> articles;
        if (category != null && !category.isBlank() && !"ALL".equalsIgnoreCase(category)) {
            articles = faqArticleRepository.findByCategoryAndIsPublishedTrueOrderByArticleIdAsc(category);
        } else {
            articles = faqArticleRepository.findByIsPublishedTrueOrderByArticleIdAsc();
        }

        if (searchQuery != null && !searchQuery.isBlank()) {
            String q = searchQuery.toLowerCase().trim();
            String[] words = q.split("\\s+");
            articles = articles.stream().filter(a -> {
                String text = (a.getQuestion() + " " + a.getAnswer() + " " + a.getCategoryLabel() + " " + (a.getKeywords() != null ? a.getKeywords() : "")).toLowerCase();
                for (String w : words) {
                    if (w.length() > 1 && text.contains(w)) {
                        return true;
                    }
                }
                return false;
            }).toList();
        }

        return articles.stream().map(a -> FaqArticleDto.builder()
                .id(a.getId())
                .articleId(a.getArticleId())
                .category(a.getCategory())
                .categoryLabel(a.getCategoryLabel())
                .question(a.getQuestion())
                .answer(a.getAnswer())
                .keywords(a.getKeywords() != null && !a.getKeywords().isBlank() ? List.of(a.getKeywords().split(",")) : List.of())
                .build()
        ).toList();
    }

    private SupportTicketDto mapToDto(SupportCustomerTicket t) {
        List<SupportMessageDto> messageDtos = t.getTicketKind() == SupportTicketKind.RESULT_REVISION
                ? List.of()
                : messageRepository.findByTicketIdOrderByCreatedAtAsc(t.getId()).stream()
                        .map(this::mapToMessageDto).toList();

        return SupportTicketDto.builder()
                .id(t.getId())
                .ticketCode(t.getTicketCode())
                .customerId(t.getCustomerId())
                .customerName(t.getCustomerName())
                .orderId(t.getOrderId())
                .missionId(t.getMissionId())
                .category(t.getCategory())
                .ticketKind(t.getTicketKind())
                .subject(t.getSubject())
                .description(t.getDescription())
                .priority(t.getPriority())
                .status(t.getStatus())
                .assignedStaffId(t.getAssignedStaffId())
                .assignedStaffName(t.getAssignedStaffName())
                .openedAt(t.getOpenedAt())
                .resolvedAt(t.getResolvedAt())
                .resolutionNotes(t.getResolutionNotes())
                .messages(messageDtos)
                .build();
    }

    private SupportMessageDto mapToMessageDto(SupportCustomerMessage m) {
        return SupportMessageDto.builder()
                .id(m.getId())
                .ticketId(m.getTicketId())
                .senderId(m.getSenderId())
                .senderName(m.getSenderName())
                .senderRole(m.getSenderRole())
                .content(m.getContent())
                .attachmentUrl(m.getAttachmentUrl())
                .createdAt(m.getCreatedAt())
                .build();
    }

    private String generateTicketCode() {
        return "TKT-" + java.time.Year.now(java.time.ZoneOffset.UTC).getValue()
                + "-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }
}
