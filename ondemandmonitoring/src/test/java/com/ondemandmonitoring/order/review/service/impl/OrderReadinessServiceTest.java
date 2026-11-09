package com.ondemandmonitoring.order.review.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.enums.ChecklistReviewStatus;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.repository.OrderChecklistItemRepository;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.review.domain.OrderReviewRecord;
import com.ondemandmonitoring.review.domain.ReviewRegionPolicy;
import com.ondemandmonitoring.review.dto.request.ManualCriterionRequest;
import com.ondemandmonitoring.review.dto.response.OrderReadinessResponse;
import com.ondemandmonitoring.review.enums.CriterionResult;
import com.ondemandmonitoring.review.enums.ReadinessRecommendation;
import com.ondemandmonitoring.review.enums.ReviewCriterionCode;
import com.ondemandmonitoring.review.enums.ReviewCriterionType;
import com.ondemandmonitoring.review.repository.OrderReviewRecordRepository;
import com.ondemandmonitoring.review.repository.ReviewRegionPolicyRepository;
import com.ondemandmonitoring.order.util.GeoReader;
import com.ondemandmonitoring.review.service.impl.OrderReadinessService;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderReadinessServiceTest {
    OrderRepository orders = mock(OrderRepository.class);
    OrderChecklistItemRepository items = mock(OrderChecklistItemRepository.class);
    OrderReviewRecordRepository records = mock(OrderReviewRecordRepository.class);
    AuthenticatedUserResolver users = mock(AuthenticatedUserResolver.class);
    ReviewRegionPolicyRepository regionPolicies = mock(ReviewRegionPolicyRepository.class);
    OrderReadinessService service;
    Order order;
    ReviewRegionPolicy policy;
    List<OrderReviewRecord> store = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new OrderReadinessService(orders, items, records, users, regionPolicies);
        order = new Order();
        order.setId("o1");
        order.setOrderStatus(OrderStatus.PENDING);
        // Inside the Ho Chi Minh City service area.
        order.setPoint(GeoReader.createPoint(106.7009, 10.7769));
        for (ReviewCriterionCode code : ReviewCriterionCode.values()) {
            if (code.getType() == ReviewCriterionType.MANUAL) {
                OrderReviewRecord record = new OrderReviewRecord();
                record.setOrder(order);
                record.setCode(code);
                record.setType(ReviewCriterionType.MANUAL);
                record.setResult(CriterionResult.PENDING);
                store.add(record);
            }
        }
        policy = regionPolicy();
        when(regionPolicies.findAllByActiveTrueOrderByPriorityDescCodeAsc()).thenAnswer(call -> List.of(policy));
        User manager = new User();
        manager.setFullName("Seed Manager");
        when(users.getCurrentUser()).thenReturn(manager);
        when(orders.findById("o1")).thenReturn(Optional.of(order));
        when(orders.findByIdForUpdate("o1")).thenReturn(Optional.of(order));
        when(items.findAllByOrderIdOrderByDisplayOrderAscIdAsc("o1")).thenReturn(List.of(item(ChecklistReviewStatus.INCLUDED)));
        when(records.findByOrderId("o1")).thenAnswer(call -> List.copyOf(store));
        when(records.findByOrderIdAndCode(any(), any())).thenAnswer(call -> store.stream()
                .filter(r -> r.getCode() == call.getArgument(1)).findFirst());
        when(records.saveAndFlush(any())).thenAnswer(call -> {
            OrderReviewRecord record = call.getArgument(0);
            if (!store.contains(record)) store.add(record);
            return record;
        });
        when(records.save(any())).thenAnswer(call -> {
            OrderReviewRecord record = call.getArgument(0);
            if (!store.contains(record)) store.add(record);
            return record;
        });
    }

    @Test
    void unansweredRequiredManualCriteriaBlockApproval() {
        var result = service.evaluate("o1");
        assertThat(result.canApprove()).isFalse();
        assertThat(result.recommendation()).isEqualTo(ReadinessRecommendation.COMPLETE_REVIEW);
        assertThat(result.blockingReasons()).anyMatch(reason -> reason.contains("Quyền truy cập hiện trường"));
        assertThat(result.criteria()).noneMatch(
                criterion -> criterion.code() == ReviewCriterionCode.AIRSPACE_PERMIT);
        assertThat(find(result, ReviewCriterionCode.SERVICE_AREA).result()).isEqualTo(CriterionResult.PASS);
    }

    @Test
    void answeringEveryRequiredManualCriterionAllowsApproval() {
        answerRequired(CriterionResult.PASS);
        var result = service.evaluate("o1");
        assertThat(result.canApprove()).isTrue();
        assertThat(result.blockingReasons()).isEmpty();
        // No site contact was entered, so the system still suggests noting the concern.
        assertThat(result.recommendation()).isEqualTo(ReadinessRecommendation.APPROVE_WITH_NOTES);
        service.requireApprovable("o1");
    }

    @Test
    void siteContactCriterionShowsTheActualContactForManagerVerification() {
        order.setSiteContactName("Nguyễn Văn An");
        order.setSiteContactPhone("0901234567");

        var criterion = find(service.evaluate("o1"), ReviewCriterionCode.SITE_CONTACT);

        assertThat(criterion.result()).isEqualTo(CriterionResult.PASS);
        assertThat(criterion.reason()).isEqualTo(
                "Người liên hệ: Nguyễn Văn An · SĐT: 0901234567");
    }

    @Test
    void failedRequiredManualCriterionBlocksApprovalAndSuggestsRejecting() {
        answerRequired(CriterionResult.PASS);
        service.answer("o1", ReviewCriterionCode.CAPABILITY,
                new ManualCriterionRequest(CriterionResult.FAIL, "Không có drone ảnh nhiệt"));
        var result = service.evaluate("o1");
        assertThat(result.canApprove()).isFalse();
        assertThat(result.recommendation()).isEqualTo(ReadinessRecommendation.REQUEST_INFO_OR_REJECT);
        assertThatThrownBy(() -> service.requireApprovable("o1"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_REVIEW_INCOMPLETE));
    }

    @Test
    void failingAnswerNeedsAReason() {
        assertThatThrownBy(() -> service.answer("o1", ReviewCriterionCode.SITE_ACCESS,
                new ManualCriterionRequest(CriterionResult.FAIL, "  ")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void manualCriteriaOnlyAcceptPassOrFail() {
        assertThatThrownBy(() -> service.answer("o1", ReviewCriterionCode.SITE_ACCESS,
                new ManualCriterionRequest(CriterionResult.WARN, null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void operationalRiskAcceptsTheMiddleLevel() {
        var result = service.answer("o1", ReviewCriterionCode.RISK_LEVEL,
                new ManualCriterionRequest(CriterionResult.WARN, null));

        assertThat(find(result, ReviewCriterionCode.RISK_LEVEL).result()).isEqualTo(CriterionResult.WARN);
    }

    @Test
    void automaticCriteriaCannotBeAnsweredByHand() {
        assertThatThrownBy(() -> service.answer("o1", ReviewCriterionCode.SERVICE_AREA,
                new ManualCriterionRequest(CriterionResult.PASS, null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void missingManualCriterionRecordIsNotCreatedByAnswer() {
        store.removeIf(record -> record.getCode() == ReviewCriterionCode.SITE_ACCESS);

        assertThatThrownBy(() -> service.answer("o1", ReviewCriterionCode.SITE_ACCESS,
                new ManualCriterionRequest(CriterionResult.PASS, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void checklistReviewIsNotDuplicatedInReadiness() {
        when(items.findAllByOrderIdOrderByDisplayOrderAscIdAsc("o1")).thenReturn(List.of(
                item(ChecklistReviewStatus.INCLUDED), item(ChecklistReviewStatus.PENDING)));
        var result = service.evaluate("o1");
        assertThat(result.criteria()).noneMatch(
                criterion -> criterion.code() == ReviewCriterionCode.CHECKLIST_REVIEWED);
    }

    @Test
    void flightScaleCriterionIsNoLongerPartOfReview() {
        order.setAltitudeM(130.0);
        policy.setMaxAltitudeM(150);

        var result = service.evaluate("o1");

        assertThat(result.criteria()).noneMatch(
                criterion -> criterion.code() == ReviewCriterionCode.FLIGHT_SCALE);
    }

    @Test
    void orderOutsideEveryDatabaseRegionFailsServiceAreaCriterion() {
        order.setPoint(GeoReader.createPoint(110.0, 20.0));

        var result = service.evaluate("o1");

        assertThat(find(result, ReviewCriterionCode.SERVICE_AREA).result()).isEqualTo(CriterionResult.FAIL);
        assertThat(find(result, ReviewCriterionCode.SERVICE_AREA).reason()).contains("database");
    }

    @Test
    void rejectedOrderCannotBeApproved() {
        answerRequired(CriterionResult.PASS);
        order.setOrderStatus(OrderStatus.REJECTED);
        assertThatThrownBy(() -> service.requireApprovable("o1")).isInstanceOf(ApiException.class);
    }

    @Test
    void approvalStoresTheAutomaticVerdictsAsEvidence() {
        answerRequired(CriterionResult.PASS);
        service.requireApprovable("o1");
        assertThat(store).anyMatch(r -> r.getType() == ReviewCriterionType.AUTO
                && r.getCode() == ReviewCriterionCode.SERVICE_AREA && r.getResult() == CriterionResult.PASS);
    }

    private void answerRequired(CriterionResult result) {
        for (ReviewCriterionCode code : ReviewCriterionCode.values()) {
            if (code.isActive() && code.getType() == ReviewCriterionType.MANUAL && code.isRequired()) {
                service.answer("o1", code, new ManualCriterionRequest(result, null));
            }
        }
    }

    private static OrderReadinessResponse.Criterion find(OrderReadinessResponse response, ReviewCriterionCode code) {
        return response.criteria().stream().filter(c -> c.code() == code).findFirst().orElseThrow();
    }

    private static OrderChecklistItem item(ChecklistReviewStatus status) {
        OrderChecklistItem item = new OrderChecklistItem();
        item.setReviewStatus(status);
        return item;
    }

    private static ReviewRegionPolicy regionPolicy() {
        ReviewRegionPolicy policy = new ReviewRegionPolicy();
        policy.setCode("HCMC");
        policy.setName("Ho Chi Minh City");
        policy.setActive(true);
        policy.setEnforceReview(true);
        policy.setBusinessTimeZone("Asia/Ho_Chi_Minh");
        policy.setMaxAltitudeM(120);
        policy.setLongRouteM(20_000);
        policy.setTightDeadlineDays(2);
        policy.setServiceArea(GeoReader.readPolygonFromCoverageArea(java.util.Map.of(
                "type", "Polygon",
                "coordinates", List.of(List.of(
                        List.of(106.0, 10.0), List.of(108.0, 10.0),
                        List.of(108.0, 12.0), List.of(106.0, 12.0), List.of(106.0, 10.0))))));
        return policy;
    }

}
