package com.ondemandmonitoring.finance;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.finance.controller.CustomerRequestAssistantController;
import com.ondemandmonitoring.finance.service.impl.QuoteAgentClient;
import com.ondemandmonitoring.pricing.dto.ChecklistPriceBreakdown;
import com.ondemandmonitoring.pricing.dto.ChecklistPricingPreviewRequests.Customer;
import com.ondemandmonitoring.pricing.dto.ChecklistPricingPreviewRequests.CustomerItem;
import com.ondemandmonitoring.pricing.dto.ServicePricePreviewResponse;
import com.ondemandmonitoring.pricing.service.ChecklistPricingPreviewService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerRequestAssistantControllerTest {
    private final ChecklistPricingPreviewService pricing = mock(ChecklistPricingPreviewService.class);
    private final QuoteAgentClient agent = mock(QuoteAgentClient.class);
    private final CustomerRequestAssistantController controller = new CustomerRequestAssistantController(pricing, agent);
    private final TestingAuthenticationToken customer = new TestingAuthenticationToken("customer-1", null, "ROLE_CUSTOMER");

    @Test void buildsContextFromAuthoritativePreviewAndDoesNotExposeInternalPolicy() {
        var selection = new Customer("service-1", List.of(new CustomerItem("item-1", null, 4, 0)));
        var item = mock(ChecklistPriceBreakdown.class);
        when(item.content()).thenReturn("Ảnh tổng quan");
        when(item.requestedImageCount()).thenReturn(4);
        when(item.includedImageCount()).thenReturn(4);
        when(item.subtotal()).thenReturn(new BigDecimal("500000"));
        var preview = new ServicePricePreviewResponse("service-1", "Giám sát công trình", "VND",
                "private-policy-id", 2, false, "CHECKLISTS", BigDecimal.ZERO, List.of(item),
                new BigDecimal("500000"), new BigDecimal("500000"), true, List.of(), false, List.of());
        when(pricing.customerQuote(selection)).thenReturn(preview);
        when(agent.explainRequest(anyString(), any(), anyString())).thenReturn(new QuoteAgentClient.Answer("READY", "Bạn sẽ nhận ảnh tổng quan.", "draft"));
        var result = controller.ask(new CustomerRequestAssistantController.Message("Tôi nhận được gì?", selection), customer);
        assertEquals("READY", result.getData().status());
        var context = ArgumentCaptor.forClass(Object.class);
        verify(agent).explainRequest(eq("Tôi nhận được gì?"), context.capture(), eq("customer-1"));
        var data = (Map<?, ?>) context.getValue();
        assertEquals(new BigDecimal("500000"), data.get("total"));
        assertFalse(data.containsKey("policyId"));
        assertTrue(data.containsKey("businessRules"));
        verify(pricing).customerQuote(selection);
        verifyNoMoreInteractions(pricing);
    }

    @Test void rejectsExistingOrderContextBeforeReadingPricing() {
        var selection = new Customer("service-1", List.of(new CustomerItem("item-1", null, 4, 0)),
                null, null, null, null, null, null, "another-order");
        assertThrows(ApiException.class, () -> controller.ask(new CustomerRequestAssistantController.Message("Giải thích", selection), customer));
        verifyNoInteractions(pricing, agent);
    }

    @Test void rejectsExistingItemContextAndEmptySelection() {
        var selection = new Customer("service-1", List.of(new CustomerItem(null, null, 4, 0, "another-item")));
        assertThrows(ApiException.class, () -> controller.ask(new CustomerRequestAssistantController.Message("Giải thích", selection), customer));
        assertThrows(ApiException.class, () -> controller.ask(new CustomerRequestAssistantController.Message("Giải thích", new Customer("s", List.of())), customer));
        verifyNoInteractions(pricing, agent);
    }
}
