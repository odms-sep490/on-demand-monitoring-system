package com.ondemandmonitoring.review.controller;
import com.ondemandmonitoring.review.service.CriterionCatalogService;
import com.ondemandmonitoring.common.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
@RestController @RequestMapping("/api/admin/order-review-criteria") @PreAuthorize("hasRole('ADMIN')") @RequiredArgsConstructor
public class AdminCriterionController {
 private final CriterionCatalogService catalog;
 @GetMapping public ApiResponse<?> list(){return ApiResponse.ok(catalog.list().stream().filter(d->d.active()).toList());}
 @PostMapping public ApiResponse<?> create(@Valid @RequestBody CriterionCatalogService.Input input){return ApiResponse.ok(catalog.create(input));}
 @PutMapping("/{code}") public ApiResponse<?> update(@PathVariable String code,@Valid @RequestBody CriterionCatalogService.Input input){return ApiResponse.ok(catalog.update(code,input));}
 @DeleteMapping("/{code}") public ApiResponse<?> delete(@PathVariable String code){catalog.delete(code);return ApiResponse.ok("Deleted");}
}
