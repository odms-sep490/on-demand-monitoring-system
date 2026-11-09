package com.ondemandmonitoring.dashboard.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Read model for the operational Manager dashboard. No JPA entity crosses the API boundary. */
public record ManagerDashboardResponse(
        Summary summary,
        List<DistributionPoint> orderStatusDistribution,
        List<ServiceDistributionPoint> serviceDistribution,
        List<DistributionPoint> additionalServiceDistribution,
        PriceAdjustmentSummary priceAdjustmentSummary,
        List<DistributionPoint> missionStatusDistribution,
        List<MissionTrendPoint> missionTrend,
        List<ActionItem> actionItems,
        List<RecentOrder> recentOrders,
        PricingPipeline pricingPipeline,
        MissionOverview missionOverview,
        List<ActiveMission> activeMissions,
        List<Activity> recentActivities,
        Instant generatedAt) {

    public record Summary(
            long totalOrders,
            BigDecimal totalQuoteValue,
            long pendingOrderCount,
            long pendingAdditionalChecklistCount,
            long waitingDepositCount,
            long activeMissionCount,
            long attentionRequiredCount) {}

    public record DistributionPoint(String key, long count) {}

    public record ServiceDistributionPoint(String serviceId, String serviceName, long count) {}

    public record PriceAdjustmentSummary(long approvedAtSystemPrice, long adjustedByManager) {}

    public record MissionTrendPoint(LocalDate periodStart, long completed, long failed) {}

    public record ActionItem(
            String id,
            String type,
            String priority,
            String orderId,
            String orderCode,
            String missionId,
            String checklistItemId,
            String title,
            String description,
            String status,
            BigDecimal systemCalculatedAmount,
            Instant createdAt,
            String actionType) {}

    public record RecentOrder(
            String orderId,
            String orderCode,
            String customerName,
            String serviceName,
            Instant createdAt,
            String status,
            long additionalChecklistCount,
            long pendingAdditionalChecklistCount,
            String pricingState) {}

    public record PricingPipeline(
            long pricingReviewCount,
            long quoteReadyCount,
            long waitingCustomerCount,
            long waitingDepositCount,
            long depositPaidCount) {}

    public record MissionOverview(
            long scheduledCount,
            long readyCount,
            long inFlightCount,
            long returningCount,
            long completedTodayCount,
            long issueCount) {}

    public record ActiveMission(
            String missionId,
            String missionCode,
            String orderId,
            String orderCode,
            String operatorName,
            String droneName,
            String status,
            Instant scheduledStartAt,
            boolean attentionRequired) {}

    public record Activity(
            String id,
            String type,
            String orderId,
            String orderCode,
            String missionId,
            String description,
            Instant occurredAt) {}
}
