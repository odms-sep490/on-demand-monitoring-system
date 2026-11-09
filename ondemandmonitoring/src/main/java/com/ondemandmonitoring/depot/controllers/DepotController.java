
package com.ondemandmonitoring.depot.controllers;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.depot.dto.request.DepotCreateRequest;
import com.ondemandmonitoring.depot.dto.request.DepotUpdateRequest;
import com.ondemandmonitoring.depot.dto.response.DepotResponse;
import com.ondemandmonitoring.depot.services.DepotService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(
        name = "Depot Management",
        description = "APIs for managing depots and finding nearest depot"
)
@RestController
@RequestMapping("/api/depots")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
public class DepotController {

    DepotService depotService;

    @Operation(summary = "Create depot")
    @PostMapping
    public ResponseEntity<ApiResponse<DepotResponse>> createDepot(
            @Valid @RequestBody DepotCreateRequest request
    ) {

        DepotResponse response = depotService.createDepot(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.created(
                        "Depot created successfully",
                        response
                ));
    }

    @Operation(summary = "Get all depots")
    @GetMapping
    public ResponseEntity<ApiResponse<List<DepotResponse>>> getAllDepots() {

        return ResponseEntity.ok(
                ApiResponse.ok(depotService.getAllDepots())
        );
    }

    @Operation(summary = "Get depot by ID")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DepotResponse>> getDepotById(
            @PathVariable String id
    ) {

        return ResponseEntity.ok(
                ApiResponse.ok(depotService.getDepotById(id))
        );
    }

    @Operation(summary = "Update depot")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<DepotResponse>> updateDepot(
            @PathVariable String id,
            @Valid @RequestBody DepotUpdateRequest request
    ) {

        DepotResponse response = depotService.updateDepot(id, request);

        return ResponseEntity.ok(
                ApiResponse.ok("Depot updated successfully", response)
        );
    }

    @Operation(summary = "Delete depot")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteDepot(
            @PathVariable String id
    ) {

        depotService.deleteDepot(id);

        return ResponseEntity.ok(
                ApiResponse.ok("Depot deleted successfully", null)
        );
    }

    @Operation(summary = "Find nearest active depot by order")
    @GetMapping("/nearest")
    public ResponseEntity<ApiResponse<DepotResponse>> findNearestDepot(
            @RequestParam String orderId
    ) {

        DepotResponse response = depotService.findNearestDepot(orderId);

        return ResponseEntity.ok(
                ApiResponse.ok(
                        "Nearest depot found successfully",
                        response
                )
        );
    }
}
