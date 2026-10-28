package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.domain.Quote;
import com.ondemandmonitoring.finance.enums.QuoteStatus;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.service.IMissionPaymentEligibilityService;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.dto.request.MissionCreateRequest;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.mapper.MissionMapper;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.service.impl.MissionService;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.*;
import com.ondemandmonitoring.order.repository.OrderRepository;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdditionalMissionCreationTest {
    @Mock MissionRepository missions;
    @Mock OrderRepository orders;
    @Mock InvoiceRepository invoices;
    @Mock IMissionPaymentEligibilityService payments;
    @Mock IMissionChecklistAssignmentService selection;
    @Mock MissionMapper mapper;
    @InjectMocks MissionService service;
    AutoCloseable mocks;
    Order order;
    MissionCreateRequest request;

    @BeforeEach void setup() {
        mocks = MockitoAnnotations.openMocks(this);
        order = new Order(); order.setId("o"); order.setOrderStatus(OrderStatus.APPROVED);
        order.setExecutionPlanStatus(ExecutionPlanStatus.FINALIZED);
        when(orders.findById("o")).thenReturn(Optional.of(order));
        request = new MissionCreateRequest(); request.setScopeLabel("Additional scope");
        request.setScheduledStartAt(Instant.parse("2026-11-01T01:00:00Z"));
        request.setScheduledEndAt(Instant.parse("2026-11-01T02:00:00Z")); request.setChecklistIds(List.of("i"));
    }
    @AfterEach void close() throws Exception { mocks.close(); }

    @Test void additionalMissionStartsPlanningAndLeavesOrderFinalized() {
        var quote = new Quote(); quote.setStatus(QuoteStatus.ACCEPTED_BY_CUSTOMER);
        var invoice = new Invoice(); invoice.setQuote(quote);
        when(invoices.findByOrderId("o")).thenReturn(Optional.of(invoice)); when(payments.isDepositSatisfied(invoice)).thenReturn(true);
        when(missions.saveAndFlush(any())).thenAnswer(call -> { Mission m = call.getArgument(0); m.setId("new"); return m; });
        service.createAdditionalMission("o", request);
        verify(missions).saveAndFlush(argThat(m -> m.getStatus() == MissionStatus.PLANNING && m.getReplacesMissionId() == null));
        verify(selection).replace(eq("new"), argThat(r -> r.items().getFirst().orderChecklistItemId().equals("i")));
        assertEquals(ExecutionPlanStatus.FINALIZED, order.getExecutionPlanStatus());
    }

    @Test void additionalEndpointRejectsDraftOrderAndNormalEndpointRejectsFinalizedOrder() {
        assertEquals(ErrorCode.EXECUTION_PLAN_NOT_DRAFT,
                assertThrows(ApiException.class, () -> service.createMission("o", request)).getErrorCode());
        order.setExecutionPlanStatus(ExecutionPlanStatus.DRAFT);
        assertEquals(ErrorCode.EXECUTION_PLAN_NOT_FINALIZED,
                assertThrows(ApiException.class, () -> service.createAdditionalMission("o", request)).getErrorCode());
        verify(missions, never()).saveAndFlush(any());
    }
}
