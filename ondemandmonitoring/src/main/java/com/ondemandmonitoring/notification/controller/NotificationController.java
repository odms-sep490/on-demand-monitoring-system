package com.ondemandmonitoring.notification.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.notification.dto.NotificationFeedResponse;
import com.ondemandmonitoring.notification.service.NotificationFeedService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
@PreAuthorize("isAuthenticated()")
public class NotificationController {

    private final NotificationFeedService notificationFeedService;

    public NotificationController(NotificationFeedService notificationFeedService) {
        this.notificationFeedService = notificationFeedService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<NotificationFeedResponse>> list(
            @RequestParam(defaultValue = "30") int limit) {
        return ResponseEntity.ok(ApiResponse.ok(notificationFeedService.listForCurrentUser(limit)));
    }
}
