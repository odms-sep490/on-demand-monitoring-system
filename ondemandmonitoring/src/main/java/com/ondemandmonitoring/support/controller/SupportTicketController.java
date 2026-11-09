package com.ondemandmonitoring.support.controller;

import com.ondemandmonitoring.support.dto.*;
import com.ondemandmonitoring.support.service.ISupportTicketService;
import com.ondemandmonitoring.user.service.IStaffDirectoryService;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import org.springframework.security.access.prepost.PreAuthorize;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for customer support ticket operations.
 * Exposes endpoints for ticket creation, querying ticket queues, fetching ticket details,
 * appending messages to thread, and updating ticket status/assignment.
 */
@RestController
@RequestMapping("/api/support-tickets")
@RequiredArgsConstructor
public class SupportTicketController {

    private final ISupportTicketService supportTicketService;
    private final IStaffDirectoryService staffDirectory;
    private final AuthenticatedUserResolver currentUser;

    /**
     * Creates a new customer support ticket.
     *
     * @param request Payload containing ticket details, category, priority, and optional operational context.
     * @return 200 OK with created support ticket DTO.
     */
    @PostMapping
    public ResponseEntity<SupportTicketDto> createTicket(@RequestBody CreateSupportTicketRequest request) {
        return ResponseEntity.ok(supportTicketService.createTicket(request));
    }

    /**
     * Lists support tickets filtered by customer ID or status queue.
     *
     * @param customerId Optional customer account filter.
     * @param status Optional ticket status filter.
     * @return List of matching support ticket DTOs.
     */
    @GetMapping
    public ResponseEntity<List<SupportTicketDto>> listTickets(
            @RequestParam(required = false) String customerId,
            @RequestParam(required = false) String status) {
        if (currentUser.getCurrentUser().getRole().getCode() == RoleCode.CUSTOMER) {
            return ResponseEntity.ok(supportTicketService.getCustomerTickets(currentUser.getCurrentUserId()));
        }
        if (customerId != null && !customerId.isBlank()) {
            return ResponseEntity.ok(supportTicketService.getCustomerTickets(customerId));
        }
        return ResponseEntity.ok(supportTicketService.getAllTickets(status));
    }

    /**
     * Retrieves details of a specific support ticket by its database ID.
     *
     * @param id Database unique identifier of the support ticket.
     * @return Support ticket DTO including full message conversation history.
     */
    @GetMapping("/{id}")
    public ResponseEntity<SupportTicketDto> getTicketById(@PathVariable String id) {
        return ResponseEntity.ok(supportTicketService.getTicketById(id));
    }

    /**
     * Appends a new response message to an existing support ticket thread.
     *
     * @param id Unique identifier of the target support ticket.
     * @param request Message payload including sender role, content, and attachment URL.
     * @return Created support message DTO.
     */
    @PostMapping("/{id}/messages")
    public ResponseEntity<SupportMessageDto> addMessage(
            @PathVariable String id,
            @RequestBody AddMessageRequest request) {
        return ResponseEntity.ok(supportTicketService.addMessage(id, request));
    }

    /**
     * Updates operational status, assigned staff member, or resolution notes of a support ticket.
     *
     * @param id Unique identifier of the target support ticket.
     * @param request Status update payload.
     * @return Updated support ticket DTO.
     */
    @PatchMapping("/{id}/status")
    public ResponseEntity<SupportTicketDto> updateTicketStatus(
            @PathVariable String id,
            @RequestBody UpdateTicketStatusRequest request) {
        return ResponseEntity.ok(supportTicketService.updateTicketStatus(id, request));
    }

    /**
     * Retrieves a list of all active STAFF users available for ticket assignment.
     * Used by Manager/Staff portal to populate the assignment dropdown with real accounts.
     *
     * @return List of objects containing staff id and fullName.
     */
    @GetMapping("/staff-agents")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<List<Map<String, String>>> getStaffAgents() {
        List<Map<String, String>> agents = staffDirectory.listActiveStaff()
                .stream()
                .map(u -> Map.of("id", u.getId(), "fullName", u.getFullName()))
                .toList();
        return ResponseEntity.ok(agents);
    }

    /**
     * Records customer feedback (helpful/unhelpful vote) on an FAQ article to PostgreSQL.
     *
     * @param request Payload with articleId, articleQuestion, customerId, and isHelpful boolean.
     * @return 200 OK with status message.
     */
    @PostMapping("/faq-feedback")
    public ResponseEntity<Map<String, String>> recordFaqFeedback(@RequestBody FaqFeedbackRequest request) {
        supportTicketService.recordFaqFeedback(request);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "FAQ feedback recorded successfully"));
    }

    /**
     * Computes real-time support analytics, SLA response metrics, and FAQ deflection rates from PostgreSQL database.
     *
     * @return SupportAnalyticsDto payload.
     */
    @GetMapping("/analytics")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<SupportAnalyticsDto> getSupportAnalytics() {
        return ResponseEntity.ok(supportTicketService.getSupportAnalytics());
    }

    /**
     * Fetches published FAQ articles directly from PostgreSQL database.
     * Supports filtering by category and search keyword query.
     *
     * @param category Optional category filter ("ORDERS", "MISSIONS", etc.)
     * @param search Optional search query string
     * @return List of FaqArticleDto objects.
     */
    @GetMapping("/faq-articles")
    public ResponseEntity<List<FaqArticleDto>> getFaqArticles(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(supportTicketService.getFaqArticles(category, search));
    }
}
