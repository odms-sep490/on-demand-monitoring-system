package com.ondemandmonitoring.order.dto.response;
import com.ondemandmonitoring.order.dto.request.OrderCreateRequest;
import java.util.Map;
public record OrderDraftValidationResponse(boolean readyForReview, OrderCreateRequest canonicalDraft,
        Map<String,String> invalidFields, Map<String,Integer> fieldSteps) {
    public OrderDraftValidationResponse(boolean ready,OrderCreateRequest draft,Map<String,String> invalid) {
        this(ready,draft,invalid,invalid.keySet().stream().collect(java.util.stream.Collectors.toMap(key->key,OrderDraftValidationResponse::step)));
    }
    private static int step(String field) {
        if(field.startsWith("service") || field.equals("flightPurpose"))return 1;
        if(field.equals("coverageArea") || field.equals("latitude") || field.equals("longitude") || field.equals("address"))return 2;
        if(field.startsWith("checklist"))return 3;
        if(field.startsWith("siteContact") || field.startsWith("permit") || field.startsWith("monitoring") || field.equals("accessNotes") || field.equals("altitudeM") || field.equals("estimatedLengthM"))return 4;
        if(field.startsWith("preferred") || field.startsWith("recurrence") || field.equals("resultDeadline"))return 5;
        return 6;
    }
}
