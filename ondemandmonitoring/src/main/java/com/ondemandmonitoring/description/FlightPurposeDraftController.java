package com.ondemandmonitoring.description;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

/** Text review only: never creates a permit or changes permit approval. */
@RestController @RequestMapping("/api/missions/{missionId}/flight-purpose-draft") @RequiredArgsConstructor
public class FlightPurposeDraftController {
    private final MissionRepository missions;
    private final OrderRepository orders;
    private final AuthenticatedUserResolver users;
    public record Purpose(String customerDraft, Instant customerConfirmedAt, String reviewedPurpose,
        Instant reviewedAt, String reviewedBy, Long revision) {}
    public record Review(@NotBlank @Size(max=1500) String purpose, @NotNull Long revision) {}
    private com.ondemandmonitoring.order.domain.Order order(String missionId) {
        return missions.findByIdWithOrder(missionId).orElseThrow(()->new ApiException(ErrorCode.MISSION_NOT_FOUND)).getOrder();
    }
    private Purpose view(com.ondemandmonitoring.order.domain.Order o) {
        return new Purpose(o.getCustomerFlightPurposeDraft(),o.getPurposeCustomerConfirmedAt(),o.getStaffReviewedFlightPurpose(),
            o.getPurposeStaffReviewedAt(),o.getPurposeStaffReviewedBy(),o.getVersion());
    }
    @GetMapping @Transactional(readOnly=true)
    @PreAuthorize("@missionAuthorizationService.canViewMission(#missionId)")
    public ApiResponse<Purpose> get(@PathVariable String missionId) {return ApiResponse.ok(view(order(missionId)));}
    @PostMapping @Transactional
    @PreAuthorize("@descriptionPurposeAuthorization.canReview(#missionId)")
    public ApiResponse<Purpose> review(@PathVariable String missionId,@Valid @RequestBody Review input) {
        var o=order(missionId);
        if(!input.revision().equals(o.getVersion())) throw new ApiException(ErrorCode.CONCURRENT_UPDATE);
        o.setStaffReviewedFlightPurpose(input.purpose());o.setPurposeStaffReviewedAt(Instant.now());o.setPurposeStaffReviewedBy(users.getCurrentUserId());
        return ApiResponse.ok(view(orders.saveAndFlush(o)));
    }
}
