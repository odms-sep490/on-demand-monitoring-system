package com.ondemandmonitoring.description;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.service.repository.ServiceRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.ondemandmonitoring.pricing.dto.ChecklistPricingPreviewRequests;
import com.ondemandmonitoring.checklist.repository.ChecklistDefinitionRepository;
import com.ondemandmonitoring.checklist.repository.ServiceChecklistRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;

@Service @RequiredArgsConstructor
public class DescriptionAssistantService {
    private final DescriptionSessionRepository sessions;
    private final AuthenticatedUserResolver users;
    private final ServiceRepository services;
    private final ChecklistDefinitionRepository definitions;
    private final ServiceChecklistRepository assignments;
    private final ObjectMapper json;
    private final Semaphore slots = new Semaphore(2);
    private final java.util.concurrent.ConcurrentHashMap<String,Instant> requests = new java.util.concurrent.ConcurrentHashMap<>();
    @Value("${DESCRIPTION_AI_URL:http://localhost:8002}") private String url;
    @Value("${DESCRIPTION_AI_TOKEN:}") private String token;

    public record Input(String sessionId, @Min(0) long revision,
        @NotBlank String serviceId, @NotNull @Size(max=2000) String description,
        @Pattern(regexp="GENERATE|REWRITE") @NotNull String mode,
        @Size(max=12) Map<@Size(max=40) String,@Size(max=1000) String> answers,
        boolean skipQuestions, @Valid ChecklistPricingPreviewRequests.Customer selection,
        @Size(max=500) String address, com.ondemandmonitoring.order.enums.OrderFlightPurpose flightPurpose) {}
    public record Confirmation(@Min(0) long revision, @NotBlank @Size(max=2000) String orderDescription,
        @NotBlank @Size(max=1500) String flightPurposeDraft) {}
    public record View(String id, long revision, String status, Map<String,Object> state, Instant confirmedAt) {}
    private JsonNode tree(DescriptionSession s) {return json.valueToTree(s.getState());}
    private View view(DescriptionSession s) { return new View(s.getId(),s.getRevision(),s.getStatus(),s.getState(),s.getConfirmedAt()); }
    private DescriptionSession owned(String id) {
        var s=sessions.findById(id).orElseThrow(()->new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if(!s.getOwnerId().equals(users.getCurrentUserId())) throw new ApiException(ErrorCode.ACCESS_DENIED);
        return s;
    }
    private void revision(DescriptionSession s,long revision) {
        if(s.getRevision()!=revision || s.getOrderId()!=null) throw new ApiException(ErrorCode.CONCURRENT_UPDATE);
    }
    @Transactional(readOnly=true) public View read(String id) { return view(owned(id)); }
    @Transactional public View generate(Input input) {
        String owner=users.getCurrentUserId();
        Instant now=Instant.now();
        requests.entrySet().removeIf(e->e.getValue().isBefore(now.minusSeconds(60)));
        var previous=requests.putIfAbsent(owner,now);
        if(previous!=null && previous.isAfter(now.minusSeconds(3)))
            throw new ApiException(ErrorCode.INVALID_REQUEST,"Vui lòng chờ một chút trước khi thử lại");
        requests.put(owner,now);
        var service=services.findById(input.serviceId()).orElseThrow(()->new ApiException(ErrorCode.SERVICE_NOT_FOUND));
        if(!Boolean.TRUE.equals(service.getIsActive())) throw new ApiException(ErrorCode.SERVICE_INACTIVE);
        if(input.mode().equals("REWRITE") && input.description().isBlank()) throw new ApiException(ErrorCode.INVALID_REQUEST);
        var s=input.sessionId()==null?new DescriptionSession():owned(input.sessionId());
        if(s.getId()!=null) revision(s,input.revision());
        if(s.getUpdatedAt()!=null && s.getUpdatedAt().isAfter(Instant.now().minusSeconds(3)))
            throw new ApiException(ErrorCode.INVALID_REQUEST,"Vui lòng chờ một chút trước khi thử lại");
        ObjectNode context=json.createObjectNode();
        context.put("description",input.description()); context.put("service",service.getName());
        if(input.address()!=null) context.put("address",input.address().strip());
        if(input.flightPurpose()!=null) context.put("flightPurpose",input.flightPurpose().name());
        if(input.selection()!=null) {
            if(!input.serviceId().equals(input.selection().serviceId()) || input.selection().orderId()!=null)
                throw new ApiException(ErrorCode.INVALID_REQUEST);
            var selected=json.createArrayNode();
            if(input.selection().items()!=null) for(var item:input.selection().items()) {
                if(item.existingItemId()!=null) throw new ApiException(ErrorCode.INVALID_REQUEST);
                String content=item.content();
                if(item.sourceChecklistId()!=null) content=definitions.findById(item.sourceChecklistId())
                    .filter(d->Boolean.TRUE.equals(d.getIsActive()))
                    .orElseThrow(()->new ApiException(ErrorCode.CHECKLIST_NOT_FOUND)).getContent();
                if(content==null || content.isBlank()) throw new ApiException(ErrorCode.INVALID_REQUEST);
                var row=selected.addObject().put("content",content);
                if(item.requestedImageCount()!=null) row.put("requestedImageCount",item.requestedImageCount());
                if(item.requestedVideoCount()!=null) row.put("requestedVideoCount",item.requestedVideoCount());
            }
            context.set("checklists",selected);
        }
        context.set("catalog",json.valueToTree(assignments.findAllByServiceIdAndChecklistIsActiveTrueOrderByDisplayOrderAscIdAsc(input.serviceId())
            .stream().map(row->Map.of("content",row.getChecklist().getContent())).toList()));
        ObjectNode answers=json.createObjectNode();
        if(s.getState()!=null && tree(s).path("context").equals(context)) {
            answers.setAll((ObjectNode)tree(s).path("answers"));
        }
        if(input.answers()!=null) input.answers().forEach(answers::put);
        var payload=json.createObjectNode().put("mode",input.mode()).put("skipQuestions",input.skipQuestions());
        payload.set("context",context); payload.set("answers",answers);
        JsonNode output=infer(payload);
        if(!Set.of("COLLECTING_INFORMATION","READY_FOR_PREVIEW").contains(output.path("status").asText()))
            throw new ApiException(ErrorCode.INVALID_REQUEST,"AI trả dữ liệu không hợp lệ");
        Set<String> fields=Set.of("status","extractedFacts","missingFields","followUpQuestions","orderDescription","flightPurposeDraft","warnings","sourceFieldPaths");
        var names=output.fieldNames();
        while(names.hasNext()) if(!fields.contains(names.next())) throw new ApiException(ErrorCode.INVALID_REQUEST,"AI output contains unknown fields");
        if(!output.path("followUpQuestions").isArray() || output.path("followUpQuestions").size()>3
            || !output.path("orderDescription").isTextual() || output.path("orderDescription").asText().length()>2000
            || !output.path("flightPurposeDraft").isTextual() || output.path("flightPurposeDraft").asText().length()>1500)
            throw new ApiException(ErrorCode.INVALID_REQUEST,"AI output is invalid");
        if(output.path("status").asText().equals("READY_FOR_PREVIEW") &&
            (output.path("orderDescription").asText().isBlank() || output.path("flightPurposeDraft").asText().isBlank()
             || output.path("orderDescription").asText().equals(output.path("flightPurposeDraft").asText())))
            throw new ApiException(ErrorCode.INVALID_REQUEST,"AI drafts are invalid");
        ObjectNode state=json.createObjectNode(); state.set("context",context);state.set("answers",answers);state.set("output",output);
        state.put("originalDescription",input.description());
        state.set("selection",json.valueToTree(input.selection()));
        s.setOwnerId(users.getCurrentUserId());s.setServiceId(input.serviceId());s.setStatus(output.path("status").asText());
        s.setState(json.convertValue(state, new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));s.setConfirmedAt(null);s.setRevision(s.getRevision()+1);
        return view(sessions.saveAndFlush(s));
    }
    private JsonNode infer(JsonNode payload) {
        if(token.isBlank() || !slots.tryAcquire()) throw new ApiException(ErrorCode.INVALID_REQUEST,"AI hiện chưa sẵn sàng. Bạn vẫn có thể nhập mô tả thủ công.");
        try {
            var request=HttpRequest.newBuilder(URI.create(url+"/description/generate"))
                .timeout(Duration.ofSeconds(100)).header("Content-Type","application/json").header("X-Internal-Token",token)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
            var response=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build().send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200 || response.body().length()>24000) throw new IllegalStateException();
            return json.readTree(response.body());
        } catch(InterruptedException e) { Thread.currentThread().interrupt();throw new ApiException(ErrorCode.INVALID_REQUEST,"AI chưa phản hồi. Hãy thử lại."); }
        catch(Exception e) { throw new ApiException(ErrorCode.INVALID_REQUEST,"AI chưa phản hồi. Bạn vẫn có thể nhập mô tả thủ công."); }
        finally {slots.release();}
    }
    @Transactional public View confirm(String id,Confirmation input) {
        var s=owned(id);revision(s,input.revision());
        if(!Set.of("READY_FOR_PREVIEW","CUSTOMER_CONFIRMED").contains(s.getStatus())) throw new ApiException(ErrorCode.INVALID_REQUEST);
        var state=(ObjectNode)tree(s).deepCopy();
        state.put("confirmedDescription",input.orderDescription().strip());state.put("confirmedFlightPurpose",input.flightPurposeDraft().strip());
        var output=(ObjectNode)state.path("output");
        output.put("orderDescription",input.orderDescription().strip());output.put("flightPurposeDraft",input.flightPurposeDraft().strip());
        s.setState(json.convertValue(state, new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));s.setStatus("CUSTOMER_CONFIRMED");s.setConfirmedAt(Instant.now());s.setRevision(s.getRevision()+1);
        return view(sessions.saveAndFlush(s));
    }
    @Transactional public void bindToOrder(String sessionId, com.ondemandmonitoring.order.domain.Order order, com.ondemandmonitoring.order.dto.request.OrderCreateRequest request) {
        var s=owned(sessionId);
        if(!"CUSTOMER_CONFIRMED".equals(s.getStatus()) || s.getOrderId()!=null
            || !s.getServiceId().equals(order.getService().getId())
            || !tree(s).path("context").path("address").asText("").equals(java.util.Objects.toString(order.getAddress(),""))
            || (tree(s).path("context").has("flightPurpose") && !tree(s).path("context").path("flightPurpose").asText().equals(java.util.Objects.toString(order.getFlightPurpose(),"")))
            || !tree(s).path("confirmedDescription").asText().equals(order.getDescription()))
            throw new ApiException(ErrorCode.CONCURRENT_UPDATE,"Please confirm the updated description draft.");
        JsonNode selected=tree(s).path("selection").path("items");
        if(selected.isArray()) {
            var expected=new java.util.TreeSet<String>();
            selected.forEach(i->expected.add(i.path("sourceChecklistId").asText()+":"+i.path("requestedImageCount").asInt()+":"+i.path("requestedVideoCount").asInt()));
            var actual=new java.util.TreeSet<String>();
            if(request.getChecklistItems()!=null) request.getChecklistItems().forEach(i->actual.add(java.util.Objects.toString(i.getSourceChecklistId(),"")+":"+java.util.Objects.toString(i.getRequestedImageCount(),"0")+":"+java.util.Objects.toString(i.getRequestedVideoCount(),"0")));
            if(!expected.equals(actual)) throw new ApiException(ErrorCode.CONCURRENT_UPDATE,"Please regenerate the draft for the selected checklist.");
        }
        order.setCustomerFlightPurposeDraft(tree(s).path("confirmedFlightPurpose").asText());
        order.setDescriptionAssistantSessionId(sessionId);
        order.setPurposeCustomerConfirmedAt(s.getConfirmedAt());
        s.setOrderId(order.getId()); sessions.save(s);
    }

}
