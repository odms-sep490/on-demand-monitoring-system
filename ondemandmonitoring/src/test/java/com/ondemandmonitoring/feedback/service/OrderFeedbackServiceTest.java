package com.ondemandmonitoring.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.delivery.repository.OrderDeliveryRepository;
import com.ondemandmonitoring.feedback.domain.OrderFeedback;
import com.ondemandmonitoring.feedback.dto.OrderFeedbackRequest;
import com.ondemandmonitoring.feedback.repository.OrderFeedbackRepository;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderFeedbackServiceTest {
    @Mock OrderFeedbackRepository feedbacks;
    @Mock OrderRepository orders;
    @Mock OrderDeliveryRepository deliveries;
    @Mock AuthenticatedUserResolver currentUser;
    @InjectMocks OrderFeedbackService service;

    private Order order;

    @BeforeEach
    void setUp() {
        User customer = new User();
        customer.setId("customer-1");
        order = new Order();
        order.setId("order-1");
        order.setCustomer(customer);
        when(orders.findById("order-1")).thenReturn(Optional.of(order));
        when(currentUser.getCurrentUserId()).thenReturn("customer-1");
        when(feedbacks.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static OrderFeedbackRequest request(int rating, String comment) {
        return new OrderFeedbackRequest(rating, 5, 4, null, comment);
    }

    @Test
    void submitsFeedbackAfterResultAccepted() {
        when(deliveries.existsByOrder_IdAndCustomerAcceptedAtIsNotNull("order-1")).thenReturn(true);

        var response = service.submit("order-1", request(4, "  Rất tốt  "));

        assertThat(response.eligible()).isFalse();
        assertThat(response.feedback().rating()).isEqualTo(4);
        assertThat(response.feedback().qualityRating()).isEqualTo(5);
        assertThat(response.feedback().comment()).isEqualTo("Rất tốt");
    }

    @Test
    void blankCommentIsStoredAsNull() {
        when(deliveries.existsByOrder_IdAndCustomerAcceptedAtIsNotNull("order-1")).thenReturn(true);

        assertThat(service.submit("order-1", request(5, "   ")).feedback().comment()).isNull();
    }

    @Test
    void rejectsBeforeResultAccepted() {
        when(deliveries.existsByOrder_IdAndCustomerAcceptedAtIsNotNull("order-1")).thenReturn(false);

        assertThatThrownBy(() -> service.submit("order-1", request(5, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_FEEDBACK_NOT_ELIGIBLE));
        verify(feedbacks, never()).saveAndFlush(any());
    }

    @Test
    void rejectsSecondSubmission() {
        when(deliveries.existsByOrder_IdAndCustomerAcceptedAtIsNotNull("order-1")).thenReturn(true);
        when(feedbacks.existsByOrder_Id("order-1")).thenReturn(true);

        assertThatThrownBy(() -> service.submit("order-1", request(5, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_FEEDBACK_ALREADY_SUBMITTED));
    }

    @Test
    void concurrentDuplicateMapsToAlreadySubmitted() {
        when(deliveries.existsByOrder_IdAndCustomerAcceptedAtIsNotNull("order-1")).thenReturn(true);
        when(feedbacks.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_order_feedbacks_order"));

        assertThatThrownBy(() -> service.submit("order-1", request(5, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_FEEDBACK_ALREADY_SUBMITTED));
    }

    @Test
    void rejectsOtherCustomer() {
        when(currentUser.getCurrentUserId()).thenReturn("someone-else");

        assertThatThrownBy(() -> service.submit("order-1", request(5, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
        assertThatThrownBy(() -> service.getForCustomer("order-1")).isInstanceOf(ApiException.class);
    }

    @Test
    void viewReportsEligibilityOrExistingFeedback() {
        when(deliveries.existsByOrder_IdAndCustomerAcceptedAtIsNotNull("order-1")).thenReturn(true);
        assertThat(service.getForCustomer("order-1").eligible()).isTrue();
        assertThat(service.getForCustomer("order-1").feedback()).isNull();

        var saved = new OrderFeedback();
        saved.setOrder(order);
        saved.setRating(2);
        when(feedbacks.findByOrder_Id("order-1")).thenReturn(Optional.of(saved));
        var view = service.getForManager("order-1");
        assertThat(view.eligible()).isFalse();
        assertThat(view.feedback().rating()).isEqualTo(2);
    }
}
