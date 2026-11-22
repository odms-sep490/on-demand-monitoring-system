package com.ondemandmonitoring.finance.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.finance.service.impl.SmartQuoteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class SmartQuoteController {
    private final SmartQuoteService service;
    private final com.ondemandmonitoring.finance.service.impl.QuoteAgentClient agent;
    @GetMapping("/customer/orders/{orderId}/quotes/{quoteId}/analysis")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<SmartQuoteService.Analysis> customer(@PathVariable String orderId, @PathVariable String quoteId) {
        return ApiResponse.ok(service.read(orderId, quoteId, true));
    }
    @GetMapping("/manager/orders/{orderId}/quotes/{quoteId}/analysis")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ApiResponse<SmartQuoteService.Analysis> manager(@PathVariable String orderId, @PathVariable String quoteId) {
        return ApiResponse.ok(service.read(orderId, quoteId, false));
    }
    @PostMapping("/customer/orders/{orderId}/quotes/{quoteId}/compare")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<SmartQuoteService.Comparison> customerCompare(@PathVariable String orderId, @PathVariable String quoteId,
            @Valid @RequestBody SmartQuoteService.Compare request) {
        return ApiResponse.ok(service.compare(orderId, quoteId, true, request));
    }
    @PostMapping("/manager/orders/{orderId}/quotes/{quoteId}/compare")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ApiResponse<SmartQuoteService.Comparison> managerCompare(@PathVariable String orderId, @PathVariable String quoteId,
            @Valid @RequestBody SmartQuoteService.Compare request) {
        return ApiResponse.ok(service.compare(orderId, quoteId, false, request));
    }
    @PostMapping("/customer/orders/{orderId}/quotes/{quoteId}/assistant/messages")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<com.ondemandmonitoring.finance.service.impl.QuoteAgentClient.Answer> customerChat(
            @PathVariable String orderId, @PathVariable String quoteId,
            @Valid @RequestBody com.ondemandmonitoring.finance.service.impl.QuoteAgentClient.Message message,
            @RequestHeader(value="Authorization", required=false) String auth) {
        return ApiResponse.ok(agent.ask(orderId, quoteId, true, message, auth));
    }
    @PostMapping("/manager/orders/{orderId}/quotes/{quoteId}/assistant/messages")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ApiResponse<com.ondemandmonitoring.finance.service.impl.QuoteAgentClient.Answer> managerChat(
            @PathVariable String orderId, @PathVariable String quoteId,
            @Valid @RequestBody com.ondemandmonitoring.finance.service.impl.QuoteAgentClient.Message message,
            @RequestHeader(value="Authorization", required=false) String auth) {
        return ApiResponse.ok(agent.ask(orderId, quoteId, false, message, auth));
    }}

