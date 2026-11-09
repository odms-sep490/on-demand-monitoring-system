package com.ondemandmonitoring.mission.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.mission.dto.response.MissionResponse;
import com.ondemandmonitoring.mission.service.IOrderMissionQueryService;
import java.util.List;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders/{orderId}/missions")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class OrderMissionController {

    IOrderMissionQueryService queryService;

    @GetMapping
    @PreAuthorize("@missionAuthorizationService.canManageMissions() or @orderAuthorizationService.canViewOrder(#orderId)")
    public ResponseEntity<ApiResponse<List<MissionResponse>>> getMissionsForOrder(
            @PathVariable String orderId) {
        return ResponseEntity.ok(ApiResponse.ok("Missions retrieved successfully",
                queryService.getMissionsByOrderId(orderId)));
    }

}
