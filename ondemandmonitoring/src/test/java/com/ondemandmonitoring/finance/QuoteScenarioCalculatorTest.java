package com.ondemandmonitoring.finance;
import com.ondemandmonitoring.finance.domain.Quote;
import com.ondemandmonitoring.finance.service.impl.QuoteScenarioCalculator;
import com.ondemandmonitoring.order.domain.*;
import com.ondemandmonitoring.order.enums.OrderChecklistSourceType;
import com.ondemandmonitoring.order.repository.OrderChecklistItemRepository;
import com.ondemandmonitoring.pricing.service.PricingEngine;
import com.ondemandmonitoring.pricing.dto.PricingBreakdown;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class QuoteScenarioCalculatorTest {
    PricingEngine engine=mock(PricingEngine.class);
    OrderChecklistItemRepository items=mock(OrderChecklistItemRepository.class);
    QuoteScenarioCalculator calculator=new QuoteScenarioCalculator(engine,items);
    Quote quote=new Quote(); Order order=new Order(); OrderChecklistItem item=new OrderChecklistItem();
    void setup(){
        order.setId("o"); quote.setOrder(order); quote.setChecklistRevision(1); quote.setTotalAmount(new BigDecimal("100000"));
        item.setId("i"); item.setPricingSnapshotVersion(2); item.setSourceType(OrderChecklistSourceType.SERVICE_TEMPLATE);
        item.setRequestedImageCount(5); item.setRequestedVideoCount(2); item.setIncludedImageCountSnapshot(5); item.setIncludedVideoCountSnapshot(1);
        item.setExtraVideoUnitPriceSnapshot(new BigDecimal("10000")); item.setExtraImageUnitPriceSnapshot(new BigDecimal("2000"));
        when(items.findAllByOrderIdAndChecklistRevisionOrderByDisplayOrderAscIdAsc("o",1)).thenReturn(List.of(item));
        when(engine.calculate(eq(order),anyList())).thenAnswer(call -> {
            List<OrderChecklistItem> rows=call.getArgument(1); var row=rows.get(0);
            var total=new BigDecimal("90000").add(new BigDecimal("10000").multiply(BigDecimal.valueOf(Math.max(0,row.getRequestedVideoCount()-1))));
            return new PricingBreakdown(null,0,"VND",total,false,true,List.of());
        });
    }
    @Test void removeVideoUsesEngineAndNeverMutatesOriginal(){setup();var result=calculator.calculate(quote,new QuoteScenarioCalculator.Change("i",null,0,null));assertEquals(new BigDecimal("90000"),result.total());assertEquals(2,item.getRequestedVideoCount());verify(engine,times(2)).calculate(eq(order),anyList());verify(items,never()).save(any());}
    @Test void addVideoUsesEngine(){setup();assertEquals(new BigDecimal("110000"),calculator.calculate(quote,new QuoteScenarioCalculator.Change("i",null,3,null)).total());}
    @Test void missingUnitPriceIsManual(){setup();item.setExtraImageUnitPriceSnapshot(null);assertNull(calculator.calculate(quote,new QuoteScenarioCalculator.Change("i",20,null,null)).total());}
    @Test void manualAdjustmentCannotBeLost(){setup();quote.setTotalAmount(new BigDecimal("80000"));assertNull(calculator.calculate(quote,new QuoteScenarioCalculator.Change("i",null,0,null)).total());}
    @Test void durationIsUnsupported(){assertNull(calculator.calculate(quote,new QuoteScenarioCalculator.Change("i",null,null,60)).total());verifyNoInteractions(engine,items);}
}
