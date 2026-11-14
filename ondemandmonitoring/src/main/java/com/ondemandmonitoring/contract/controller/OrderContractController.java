package com.ondemandmonitoring.contract.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.contract.dto.*;
import com.ondemandmonitoring.contract.service.IOrderContractService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/orders/{orderId}/contract")
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasAnyRole('CUSTOMER','MANAGER','ADMIN')")
public class OrderContractController {
  private final IOrderContractService contracts;

  @GetMapping
  public ApiResponse<ContractResponse> get(@PathVariable String orderId) {
    return ApiResponse.ok(contracts.get(orderId));
  }

  @PostMapping(value = "/issue", consumes = "multipart/form-data")
  @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
  public ApiResponse<ContractResponse> issue(
      @PathVariable String orderId,
      @RequestParam @NotBlank String quoteId,
      @RequestParam @NotBlank @Size(max = 80) String contractNumber,
      @RequestPart("file") MultipartFile file) {
    return ApiResponse.ok(contracts.issue(orderId, quoteId, contractNumber, file));
  }

  @PostMapping(value = "/submissions", consumes = "multipart/form-data")
  @PreAuthorize("hasRole('CUSTOMER')")
  public ApiResponse<ContractResponse> submit(
      @PathVariable String orderId,
      @RequestParam @PositiveOrZero long expectedVersion,
      @RequestPart("file") MultipartFile file) {
    return ApiResponse.ok(contracts.submit(orderId, expectedVersion, file));
  }

  @PostMapping("/confirm")
  @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
  public ApiResponse<ContractResponse> confirm(
      @PathVariable String orderId, @Valid @RequestBody ContractReviewRequest request) {
    return ApiResponse.ok(contracts.confirm(orderId, request));
  }

  @PostMapping("/request-changes")
  @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
  public ApiResponse<ContractResponse> requestChanges(
      @PathVariable String orderId, @Valid @RequestBody ContractReviewRequest request) {
    return ApiResponse.ok(contracts.requestChanges(orderId, request));
  }

  @PostMapping("/download")
  public ApiResponse<ContractDownloadResponse> download(
      @PathVariable String orderId, @RequestParam(required = false) String submissionId) {
    return ApiResponse.ok(contracts.download(orderId, submissionId));
  }
}
