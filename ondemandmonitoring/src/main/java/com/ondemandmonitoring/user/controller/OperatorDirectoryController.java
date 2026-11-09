package com.ondemandmonitoring.user.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.user.dto.response.AvailableOperatorResponse;
import com.ondemandmonitoring.user.service.IOperatorDirectoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Operator Directory", description = "APIs for mission resource assignment")
@RestController
@RequestMapping("/api/operators")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class OperatorDirectoryController {

    IOperatorDirectoryService operatorDirectoryService;

    @Operation(summary = "Get available device operators", description = "Returns active device operator accounts")
    @PreAuthorize("hasAnyRole('MANAGER', 'STAFF', 'ADMIN')")
    @GetMapping("/available")
    public ResponseEntity<ApiResponse<List<AvailableOperatorResponse>>> getAvailableOperators(
            @RequestParam(required = false) String missionId) {
        return ResponseEntity.ok(ApiResponse.ok(operatorDirectoryService.getAvailableOperators(missionId)));
    }
}
