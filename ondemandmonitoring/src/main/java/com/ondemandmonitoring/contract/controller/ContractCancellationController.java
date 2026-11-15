package com.ondemandmonitoring.contract.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.contract.dto.*;
import com.ondemandmonitoring.contract.service.IContractCancellationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/orders/{orderId}/contract/cancellation")
@PreAuthorize("hasAnyRole('CUSTOMER','MANAGER','ADMIN')")
public class ContractCancellationController {
  private final IContractCancellationService service;

  @GetMapping
  public ApiResponse<ContractCancellationResponse> get(@PathVariable String orderId) {
    return ApiResponse.ok(service.get(orderId));
  }

  @PostMapping
  @PreAuthorize("hasRole('MANAGER')")
  public ApiResponse<ContractCancellationResponse> request(
      @PathVariable String orderId, @Valid @RequestBody ContractCancellationRequest request) {
    return ApiResponse.ok(service.request(orderId, request));
  }

  @PostMapping("/decision")
  @PreAuthorize("hasRole('MANAGER')")
  public ApiResponse<ContractCancellationResponse> decide(
      @PathVariable String orderId, @Valid @RequestBody ContractCancellationDecision request) {
    return ApiResponse.ok(service.decide(orderId, request));
  }
}
