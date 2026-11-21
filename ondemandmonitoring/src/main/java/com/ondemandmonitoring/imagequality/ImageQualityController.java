package com.ondemandmonitoring.imagequality;

import com.ondemandmonitoring.common.api.ApiResponse;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
public class ImageQualityController {
    private final ImageQualityService service;
    public record Request(UUID requestKey,String profile) {}
    public record Review(String decision,String note) {}
    @PostMapping("/api/customer/media/{mediaId}/quality-checks") @PreAuthorize("hasRole('CUSTOMER')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<?> customerRequest(@PathVariable String mediaId,@RequestBody Request body) {
        return ApiResponse.ok(service.request(mediaId,body.requestKey(),body.profile(),true));
    }
    @GetMapping("/api/customer/media/{mediaId}/quality-checks") @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<?> customerHistory(@PathVariable String mediaId) { return ApiResponse.ok(service.history(mediaId,true)); }
    @PostMapping("/api/mission-media/{mediaId}/quality-checks") @PreAuthorize("hasAnyRole('STAFF','MANAGER','ADMIN')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<?> staffRequest(@PathVariable String mediaId,@RequestBody Request body) {
        return ApiResponse.ok(service.request(mediaId,body.requestKey(),body.profile(),false));
    }
    @GetMapping("/api/mission-media/{mediaId}/quality-checks") @PreAuthorize("hasAnyRole('STAFF','MANAGER','ADMIN')")
    public ApiResponse<?> staffHistory(@PathVariable String mediaId) { return ApiResponse.ok(service.history(mediaId,false)); }
    @GetMapping("/api/image-quality/checks/{id}") @PreAuthorize("hasAnyRole('CUSTOMER','STAFF','MANAGER','ADMIN')")
    public ApiResponse<?> get(@PathVariable UUID id) { return ApiResponse.ok(service.read(id)); }
    @DeleteMapping("/api/image-quality/checks/{id}") @PreAuthorize("hasAnyRole('CUSTOMER','STAFF','MANAGER','ADMIN')")
    public ApiResponse<?> cancel(@PathVariable UUID id) { return ApiResponse.ok(service.cancel(id)); }
    @PostMapping("/api/image-quality/checks/{id}/reviews") @PreAuthorize("hasAnyRole('STAFF','MANAGER','ADMIN')")
    public ApiResponse<?> review(@PathVariable UUID id,@RequestBody Review body) { return ApiResponse.ok(service.review(id,body.decision(),body.note())); }
    @GetMapping("/api/missions/{missionId}/media/quality-checks") @PreAuthorize("hasAnyRole('STAFF','MANAGER','ADMIN')")
    public ApiResponse<?> list(@PathVariable String missionId) { return ApiResponse.ok(service.mission(missionId)); }
    @PostMapping("/api/missions/{missionId}/media/quality-checks") @PreAuthorize("hasAnyRole('STAFF','MANAGER','ADMIN')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<?> batch(@PathVariable String missionId,@RequestBody Request body) { return ApiResponse.ok(service.batch(missionId,body.requestKey(),body.profile())); }
}
