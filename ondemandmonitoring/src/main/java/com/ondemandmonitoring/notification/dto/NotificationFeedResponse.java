package com.ondemandmonitoring.notification.dto;

import java.util.List;

public record NotificationFeedResponse(List<NotificationItemResponse> items) {
}
