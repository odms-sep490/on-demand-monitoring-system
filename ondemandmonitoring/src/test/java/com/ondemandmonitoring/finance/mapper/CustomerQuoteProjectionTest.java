package com.ondemandmonitoring.finance.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.finance.domain.Quote;
import com.ondemandmonitoring.finance.domain.QuoteItem;
import com.ondemandmonitoring.finance.enums.QuoteItemType;
import com.ondemandmonitoring.finance.enums.QuoteStatus;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.enums.OrderChecklistSourceType;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class CustomerQuoteProjectionTest {
    @Test void managerAndCustomerReceiveTheSameFrozenGuidanceSnapshot() {
        Order order = new Order(); order.setId("order-1");
        Quote quote = new Quote(); quote.setOrder(order); quote.setTotalAmount(BigDecimal.TEN);
        var snapshot = new com.ondemandmonitoring.finance.dto.ChecklistGuidanceSnapshot(1,
                List.of(new com.ondemandmonitoring.finance.dto.ChecklistGuidanceSnapshot.Item("item-1", 2, 3,
                        "Approved scope", List.of("Inspect"), List.of("Access"), List.of("Photos"))));
        quote.setChecklistGuidanceSnapshot(snapshot);
        assertThat(FinanceMapper.quote(quote).checklistGuidanceSnapshot()).isEqualTo(snapshot);
        assertThat(FinanceMapper.customerQuote(quote).checklistGuidanceSnapshot()).isEqualTo(snapshot);
    }

    @Test void customerQuoteCollapsesInternalCalculationAndAdjustmentIntoFinalAdditionalPrice() throws Exception {
        Order order = new Order(); order.setId("order-1");
        OrderChecklistItem custom = new OrderChecklistItem();
        custom.setId("item-1"); custom.setContent("Inspect rear roof");
        custom.setSourceType(OrderChecklistSourceType.CUSTOMER_CUSTOM);
        custom.setSystemCalculatedPrice(new BigDecimal("880000"));
        custom.setFinalAmount(new BigDecimal("800000"));
        custom.setManagerPriceAdjustmentReason("Internal reason");

        Quote quote = new Quote(); quote.setId("quote-1"); quote.setOrder(order); quote.setQuoteVersion(1);
        quote.setStatus(QuoteStatus.APPROVED); quote.setTotalAmount(new BigDecimal("3000000"));
        QuoteItem system = line(quote, custom, QuoteItemType.MEDIA, "Internal system calculation", "880000", 0);
        QuoteItem adjustment = line(quote, custom, QuoteItemType.MANAGER_ADJUSTMENT, "Internal reason", "-80000", 1);
        quote.setItems(List.of(system, adjustment));

        var response = FinanceMapper.customerQuote(quote);
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);

        assertThat(response.estimatedBaseAmount()).isEqualByComparingTo("2200000");
        assertThat(response.approvedAdditionalServiceAmount()).isEqualByComparingTo("800000");
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.type()).isEqualTo(QuoteItemType.ADDITIONAL);
            assertThat(item.amount()).isEqualByComparingTo("800000");
        });
        assertThat(json).doesNotContain("880000", "-80000", "Internal reason", "systemCalculated",
                "difference", "riskFlags");
    }

    private QuoteItem line(Quote quote, OrderChecklistItem item, QuoteItemType type, String description,
            String amount, int order) {
        QuoteItem line = new QuoteItem(); line.setId("line-" + order); line.setQuote(quote);
        line.setOrderChecklistItem(item); line.setType(type); line.setDescription(description);
        line.setQuantity(BigDecimal.ONE); line.setUnitPrice(new BigDecimal(amount));
        line.setAmount(new BigDecimal(amount)); line.setDisplayOrder(order); return line;
    }
}
