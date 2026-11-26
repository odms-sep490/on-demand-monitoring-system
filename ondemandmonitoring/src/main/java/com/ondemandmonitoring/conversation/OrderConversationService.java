package com.ondemandmonitoring.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.order.dto.request.OrderCreateRequest;
import com.ondemandmonitoring.order.service.IOrderService;
import com.ondemandmonitoring.checklist.repository.ServiceChecklistRepository;
import com.ondemandmonitoring.service.repository.ServiceRepository;
import com.ondemandmonitoring.service.repository.ServiceDeliverableRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.ondemandmonitoring.warehouse.repository.PreferredTimeRepository;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;

/** The model proposes; only customer confirmation and shared business validation commit data. */
@Service @RequiredArgsConstructor
public class OrderConversationService {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(OrderConversationService.class);
    private final OrderConversationRepository sessions;
    private final AuthenticatedUserResolver users;
    private final ServiceRepository services;
    private final ServiceChecklistRepository checklists;
    private final ServiceDeliverableRepository deliverables;
    private final PreferredTimeRepository times;
    private final IOrderService orders;
    private final ObjectMapper json;
    private final java.util.concurrent.Semaphore inferenceSlots = new java.util.concurrent.Semaphore(2);
    @Value("${DESCRIPTION_AI_URL:http://localhost:8002}") private String workerUrl;
    @Value("${DESCRIPTION_AI_TOKEN:}") private String workerToken;
    public record Revision(@Min(0) long revision) {}
    public record DescriptionReview(@Min(0) long revision, @NotBlank String serviceId,
            @NotBlank @Size(max=2000) String originalText,
            @Size(max=2000) String answer, @NotBlank @Pattern(regexp="review|generate|restart") String action) {}
    public record Message(@Min(0) long revision,@NotBlank @Size(max=80) String requestId,@NotBlank @Size(max=2000) String text,
            @Size(max=20) Map<String,Object> customerContext) {
        public Message(long revision,String requestId,String text){this(revision,requestId,text,Map.of());}
    }
    // The payload is deliberately not @Valid: missing fields are returned as draft errors, never as an Order.
    public record Validation(@Min(0) long revision,@NotNull OrderCreateRequest draft) {}
    public record View(String id,long revision,Instant expiresAt,Map<String,Object> state) {}
    private static final Set<String> PROPOSABLE = Set.of("serviceId","description","flightPurpose","otherFlightPurpose",
        "siteContactName","siteContactPhone","accessNotes","altitudeM","estimatedLengthM","monitoringRelationship",
        "preferredDateFrom","preferredDateTo","preferredTimeId","resultDeadline","recurrenceType","recurrenceOccurrences",
        "weatherFallback","checklistItems");
    private View view(OrderConversationSession s) {return new View(s.getId(),s.getRevision(),s.getExpiresAt(),s.getState());}
    private OrderConversationSession owned(String id) {
        var s=sessions.findById(id).orElseThrow(()->new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if(!s.getOwnerId().equals(users.getCurrentUserId())) throw new ApiException(ErrorCode.ACCESS_DENIED);
        if(s.getExpiresAt().isBefore(Instant.now())) throw new ApiException(ErrorCode.INVALID_REQUEST,"Phiên tư vấn đã hết hạn. Hãy mở phiên mới.");
        // Copy JSON state before mutation, including when Hibernate dirty checking is deferred.
        s.setState(new LinkedHashMap<>(s.getState()));
        return s;
    }
    private void revision(OrderConversationSession s,long revision) {
        if(s.getRevision()!=revision) throw new ApiException(ErrorCode.CONCURRENT_UPDATE);
    }
    private View save(OrderConversationSession s) {
        s.setRevision(s.getRevision()+1);
        return view(sessions.saveAndFlush(s));
    }
    @Transactional public View create() {
        var s=new OrderConversationSession(); s.setOwnerId(users.getCurrentUserId());
        s.setExpiresAt(Instant.now().plus(Duration.ofHours(24)));
        s.setState(new LinkedHashMap<>(Map.of("messages",List.of(),"confirmedFields",Map.of(),"pendingChanges",Map.of(),"readyForReview",false)));
        return view(sessions.saveAndFlush(s));
    }
    @Transactional public void delete(String id) {
        var s=sessions.findById(id).orElseThrow(()->new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if(!s.getOwnerId().equals(users.getCurrentUserId())) throw new ApiException(ErrorCode.ACCESS_DENIED);
        sessions.delete(s);
    }
    @Transactional(readOnly=true) public View read(String id) {return view(owned(id));}
    @SuppressWarnings("unchecked")
    @Transactional public View reviewDescription(String id, DescriptionReview input) {
        var session=owned(id); revision(session,input.revision());
        Map<String,Object> service=((List<Map<String,Object>>)catalog().get("services")).stream()
                .filter(row->input.serviceId().equals(row.get("id"))).findFirst()
                .orElseThrow(()->new ApiException(ErrorCode.SERVICE_NOT_FOUND));
        Map<String,Object> previous=(Map<String,Object>)session.getState().getOrDefault("descriptionReview",Map.of());
        boolean restart=input.action().equals("restart") || !input.serviceId().equals(previous.get("serviceId"))
                || !input.originalText().equals(previous.get("originalText"));
        List<String> statements=new ArrayList<>();
        if(!restart && previous.get("statements") instanceof List<?> saved) {
            for(Object statement:saved) statements.add(Objects.toString(statement,""));
        } else statements.add(input.originalText());
        if(input.answer()!=null && !input.answer().isBlank()) statements.add(input.answer().strip());
        if(statements.size()>25) throw new ApiException(ErrorCode.INVALID_REQUEST,"Cuộc tư vấn đã đủ dài. Hãy xem và áp dụng mô tả hiện tại.");
        if(workerToken.isBlank()) throw new ApiException(ErrorCode.INVALID_REQUEST,"Chưa cấu hình kết nối AI.");
        if(!inferenceSlots.tryAcquire()) throw new ApiException(ErrorCode.INVALID_REQUEST,"Trợ lý đang bận. Vui lòng thử lại.");
        try {
            var payload=Map.of("service",service,"originalText",input.originalText(),"statements",statements,
                    "action",input.action().equals("generate")?"generate":"review");
            var request=HttpRequest.newBuilder(URI.create(workerUrl+"/conversation/description-review"))
                    .timeout(Duration.ofSeconds(85)).header("Content-Type","application/json")
                    .header("X-Internal-Token",workerToken)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
            var response=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build()
                    .send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200 || response.body().length()>60000)
                throw new ApiException(ErrorCode.INVALID_REQUEST,"Chưa đánh giá được mô tả. Câu trả lời trước vẫn được giữ.");
            Map<String,Object> result=json.readValue(response.body(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
            result.put("serviceId",input.serviceId());
            session.getState().put("descriptionReview",result);
            // Description consultation never mutates canonical fields or creates an Order.
            return save(session);
        } catch(InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.INVALID_REQUEST,"Đã dừng đánh giá mô tả.");
        } catch(ApiException error) {throw error;}
        catch(Exception error) {throw new ApiException(ErrorCode.INVALID_REQUEST,"Chưa kết nối được trợ lý mô tả. Hãy thử lại.");}
        finally {inferenceSlots.release();}
    }
    @Transactional(readOnly=true) public byte[] speech(String id,String text) {
        owned(id);
        return synthesizeSpeech(text);
    }
    public byte[] synthesizeSpeech(String text) {
        try {
            var request=HttpRequest.newBuilder(URI.create(workerUrl+"/conversation/speech"))
                .timeout(Duration.ofSeconds(245)).header("Content-Type","application/json")
                .header("X-Internal-Token",workerToken)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("text",text)))).build();
            var response=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build()
                    .send(request,HttpResponse.BodyHandlers.ofByteArray());
            var audio=response.body();
            if(response.statusCode()!=200 || audio.length<44 || audio.length>20*1024*1024
                || audio[0]!='R' || audio[1]!='I' || audio[2]!='F' || audio[3]!='F')
                throw new ApiException(ErrorCode.INVALID_REQUEST,"Dịch vụ giọng nói đang bận. Hãy thử nghe lại.");
            return audio;
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new ApiException(ErrorCode.INVALID_REQUEST,"Đã dừng giọng nói."); }
        catch(ApiException e) { throw e; }
        catch(Exception e) { throw new ApiException(ErrorCode.INVALID_REQUEST,"Chưa kết nối được giọng đọc tiếng Việt."); }
    }
    @SuppressWarnings("unchecked")
    @Transactional public View message(String id,Message input) {
        var s=owned(id); var state=s.getState();
        // A repeated request is safe even if its original revision is now stale.
        var history=new ArrayList<>((List<Map<String,Object>>)state.getOrDefault("messages",List.of()));
        var duplicate=history.stream().filter(m->input.requestId().equals(m.get("requestId"))).findFirst();
        if(duplicate.isPresent()) {
            if(!input.text().equals(duplicate.get().get("content"))) throw new ApiException(ErrorCode.CONCURRENT_UPDATE);
            return view(s);
        }
        revision(s,input.revision());
        if(history.size()>=80) throw new ApiException(ErrorCode.INVALID_REQUEST,"Phiên tư vấn đã đủ dài. Hãy tiếp tục bằng biểu mẫu hoặc mở phiên mới.");
        history.add(Map.of("role","user","content",input.text(),"requestId",input.requestId()));
        Map<String,Object> customerContext=input.customerContext()==null?Map.of():input.customerContext();
        if(!Set.of("locationConfirmed","address","preferredDateFrom","preferredDateTo","preferredTimeId","siteContactName","siteContactPhone",
                "accessNotes","monitoringRelationship","monitoringAuthorizationConfirmed","termsAccepted").containsAll(customerContext.keySet())
                || json.valueToTree(customerContext).toString().length()>5000) throw new ApiException(ErrorCode.INVALID_REQUEST);
        state.put("customerContext",customerContext);
        Map<String,Object> catalog=catalog();
        Map<String,Object> result=infer(Map.of("messages",history,"confirmedFields",state.getOrDefault("confirmedFields",Map.of()),
                "pendingChanges",state.getOrDefault("pendingChanges",Map.of()),"catalog",catalog,
                "customerContext",customerContext,"today",LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).toString()));
        Object raw=result.get("proposedChanges");
        if(!(raw instanceof Map<?,?>)) throw new ApiException(ErrorCode.INVALID_REQUEST,"AI trả dữ liệu không hợp lệ. Nội dung đã xác nhận vẫn được giữ.");
        Map<String,Object> proposed=new LinkedHashMap<>((Map<String,Object>)raw);
        if(!PROPOSABLE.containsAll(proposed.keySet())) throw new ApiException(ErrorCode.INVALID_REQUEST,"AI đề xuất trường không được phép.");
        checkCatalog(proposed,Objects.toString(((Map<?,?>)state.get("confirmedFields")).get("serviceId"),""));
        String reply=Objects.toString(result.get("assistantMessage"),"").strip();
        if(reply.isBlank() || reply.length()>2000) throw new ApiException(ErrorCode.INVALID_REQUEST,"AI chưa trả lời hợp lệ.");
        String action=Objects.toString(result.get("requestedUiAction"),"");
        if(!Set.of("","MAP","SCHEDULE","SITE_DETAILS","REVIEW").contains(action)) throw new ApiException(ErrorCode.INVALID_REQUEST);
        history.add(Map.of("role","assistant","content",reply));
        state.put("messages",history); state.put("pendingChanges",proposed); state.put("requestedUiAction",action);
        state.put("lastToolCalls",result.getOrDefault("toolCalls",List.of()));
        state.put("missingFields",result.getOrDefault("missingFields",List.of()));
        state.put("readyForReview",false); state.remove("canonicalDraft");
        return save(s);
    }
    @SuppressWarnings("unchecked")
    @Transactional public View confirm(String id,long expectedRevision) {
        var s=owned(id); revision(s,expectedRevision); var state=s.getState();
        var pending=(Map<String,Object>)state.getOrDefault("pendingChanges",Map.of());
        checkCatalog(pending,Objects.toString(((Map<?,?>)state.get("confirmedFields")).get("serviceId"),""));
        var confirmed=new LinkedHashMap<>((Map<String,Object>)state.getOrDefault("confirmedFields",Map.of()));
        if(pending.containsKey("serviceId") && !Objects.equals(pending.get("serviceId"),confirmed.get("serviceId"))) {
            confirmed.remove("checklistItems");
        }
        confirmed.putAll(pending); state.put("confirmedFields",confirmed); state.put("pendingChanges",Map.of());
        state.put("readyForReview",false); state.remove("canonicalDraft");
        return save(s);
    }
    @Transactional public View validate(String id,Validation input) {
        var s=owned(id); revision(s,input.revision());
        if(s.getState().get("pendingChanges") instanceof Map<?,?> pending && !pending.isEmpty())
            throw new ApiException(ErrorCode.INVALID_REQUEST,"Vui lòng xác nhận hoặc sửa đề xuất của trợ lý trước khi kiểm tra bản nháp.");
        var result=orders.validateDraftForReview(input.draft());
        s.getState().put("customerDraft",snapshot(input.draft()));
        // Avoid storing JsonNode (Spring's HTTP and Hibernate use different Jackson generations).
        s.getState().put("validation",snapshot(result));
        s.getState().put("readyForReview",result.readyForReview());
        if(result.readyForReview()) s.getState().put("canonicalDraft",snapshot(result.canonicalDraft()));
        else s.getState().remove("canonicalDraft");
        return save(s);
    }
    private Map<String,Object> snapshot(Object value) {
        return json.copy().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).convertValue(value,Map.class);
    }
    private void checkCatalog(Map<String,Object> fields,String confirmedServiceId) {
        try {
            // Enums and field constraints are checked at the trusted Spring boundary,
            // even when the Python worker has already checked its own schema.
            var orderFields=new LinkedHashMap<>(fields);orderFields.remove("otherFlightPurpose");
            var partial=json.convertValue(orderFields,OrderCreateRequest.class);
            try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()) {
                for(String field:fields.keySet()) {
                    if(field.equals("otherFlightPurpose"))continue; // UI-only detail, not an OrderCreateRequest property.
                    if(field.equals("estimatedLengthM"))continue; // The conversational form holds this as text until payload conversion.
                    var violations=factory.getValidator().validateProperty(partial,field);
                    if(!violations.isEmpty())throw new ApiException(ErrorCode.INVALID_REQUEST,"Thông tin đề xuất chưa hợp lệ: "+field);
                }
            }
        }catch(IllegalArgumentException e){throw new ApiException(ErrorCode.INVALID_REQUEST,"Đề xuất chứa lựa chọn hoặc kiểu dữ liệu không hợp lệ.");}
        if(fields.get("serviceId")!=null) {
            var service=services.findById(fields.get("serviceId").toString()).orElseThrow(()->new ApiException(ErrorCode.SERVICE_NOT_FOUND));
            if(!Boolean.TRUE.equals(service.getIsActive())) throw new ApiException(ErrorCode.SERVICE_INACTIVE);
        }
        if(fields.get("checklistItems") instanceof List<?> items) {
            String selectedServiceId=Objects.toString(fields.getOrDefault("serviceId",confirmedServiceId),"");
            var currentCatalog=checklists.findAll();
            Set<String> selected=new HashSet<>();
            for(Object row:items) {
                if(!(row instanceof Map<?,?> item) || !(item.get("sourceChecklistId") instanceof String checklistId))
                    throw new ApiException(ErrorCode.INVALID_REQUEST,"Chỉ đề xuất checklist có trong catalog.");
                var found=currentCatalog.stream().filter(sc->sc.getService().getId().equals(selectedServiceId)
                        && sc.getChecklist().getId().equals(checklistId)
                        && Boolean.TRUE.equals(sc.getChecklist().getIsActive()) && Boolean.TRUE.equals(sc.getService().getIsActive())).findFirst();
                if(found.isEmpty() || !(item.get("expectedChecklistVersion") instanceof Number version)
                        || version.longValue()!=found.get().getChecklist().getVersion() || !selected.add(checklistId))
                    throw new ApiException(ErrorCode.ORDER_CHECKLIST_TEMPLATE_CHANGED);
                for(String count:List.of("requestedImageCount","requestedVideoCount")) {
                    if(!(item.get(count) instanceof Number number) || number.doubleValue()!=number.intValue() || number.intValue()<0 || number.intValue()>500)
                        throw new ApiException(ErrorCode.INVALID_REQUEST,"Số lượng ảnh/video chưa hợp lệ.");
                }
            }
        }
    }
    private Map<String,Object> catalog() {
        var available=services.findAllByIsActiveTrue().stream().map(service->{
            Map<String,Object> row=new LinkedHashMap<>();row.put("id",service.getId()); row.put("name",service.getName());
            row.put("description",Objects.toString(service.getDescription(),""));
            row.put("indicativeStandardPrice",service.getDefaultPrice());
            row.put("checklists",checklists.findAllByServiceIdAndChecklistIsActiveTrueOrderByDisplayOrderAscIdAsc(service.getId()).stream().map(sc->{
                var c=sc.getChecklist();Map<String,Object> item=new LinkedHashMap<>();
                item.put("id",c.getId());item.put("version",c.getVersion());item.put("content",c.getContent());
                item.put("required",sc.isRequired());
                item.put("includedImageCount",sc.getIncludedImageCountOverride()==null?c.getIncludedImageCount():sc.getIncludedImageCountOverride());
                item.put("includedVideoCount",sc.getIncludedVideoCountOverride()==null?c.getIncludedVideoCount():sc.getIncludedVideoCountOverride());
                return item;
            }).toList());
            row.put("deliverables",deliverables.findAllByServiceId(service.getId()).stream().map(d->Map.of("id",d.getDeliverableType().getId(),"name",d.getDeliverableType().getName())).toList());
            return row;
        }).toList();
        var required=Arrays.stream(OrderCreateRequest.class.getDeclaredFields()).filter(field->field.isAnnotationPresent(jakarta.validation.constraints.NotNull.class)
                || field.isAnnotationPresent(jakarta.validation.constraints.NotBlank.class) || field.isAnnotationPresent(jakarta.validation.constraints.NotEmpty.class))
                .map(java.lang.reflect.Field::getName).toList();
        return Map.of("services",available,"requiredFields",required,"preferredTimes",times.findAll().stream().map(t->Map.of("id",t.getId(),"name",t.getName(),"startTime",t.getStartTime().toString(),"endTime",t.getEndTime().toString())).toList(),
                "earliestDate",LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(7).toString());
    }
    private Map<String,Object> infer(Map<String,Object> payload) {
        if(workerToken.isBlank()) throw new ApiException(ErrorCode.INVALID_REQUEST,"Chưa cấu hình kết nối AI.");
        if(!inferenceSlots.tryAcquire()) throw new ApiException(ErrorCode.INVALID_REQUEST,"Trợ lý đang bận. Vui lòng thử lại sau một chút.");
        try {
            var request=HttpRequest.newBuilder(URI.create(workerUrl+"/conversation/turn")).timeout(Duration.ofSeconds(95))
                    .header("Content-Type","application/json").header("X-Internal-Token",workerToken)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
            var response=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build().send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200) {
                String detail="";
                try {detail=json.readTree(response.body()).path("detail").asText();}catch(Exception ignored){}
                LOG.warn("Order assistant worker HTTP {} code {}",response.statusCode(),detail.matches("[A-Z_]{1,80}")?detail:"UNSPECIFIED");
                if(detail.equals("ORDER_AGENT_OUTPUT_INVALID"))throw new ApiException(ErrorCode.INVALID_REQUEST,"Trợ lý chưa hiểu đúng thông tin. Hãy thử diễn đạt rõ hơn hoặc tiếp tục nhập thủ công; dữ liệu đã xác nhận được giữ nguyên.");
                if(detail.equals("ORDER_AGENT_TIMEOUT"))throw new ApiException(ErrorCode.INVALID_REQUEST,"Trợ lý xử lý quá lâu. Hãy thử lại; dữ liệu đã xác nhận được giữ nguyên.");
                throw new IllegalStateException();
            }
            if(response.body().length()>30000) throw new IllegalStateException();
            return json.readValue(response.body(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        } catch(InterruptedException e) {Thread.currentThread().interrupt();throw new ApiException(ErrorCode.INVALID_REQUEST,"Đã dừng kết nối AI. Bạn vẫn có thể nhập thủ công.");}
        catch(ApiException e) {throw e;}
        catch(Exception e) {throw new ApiException(ErrorCode.INVALID_REQUEST,"Chưa kết nối được trợ lý. Hãy thử lại; dữ liệu đã xác nhận vẫn được giữ.");}
        finally {inferenceSlots.release();}
    }
    public Map<String,Object> transcribe(org.springframework.web.multipart.MultipartFile audio) {
        if(audio.isEmpty() || audio.getSize()>10*1024*1024 || workerToken.isBlank())
            throw new ApiException(ErrorCode.INVALID_REQUEST,"Bản ghi âm không hợp lệ hoặc vượt quá 10 MB.");
        String type=Objects.toString(audio.getContentType(),"");
        if(!Set.of("audio/webm","audio/wav","audio/ogg","audio/mp4").contains(type.split(";")[0]))
            throw new ApiException(ErrorCode.INVALID_REQUEST,"Định dạng ghi âm chưa được hỗ trợ.");
        try {
            var req=HttpRequest.newBuilder(URI.create(workerUrl+"/conversation/transcribe")).timeout(Duration.ofSeconds(220))
                    .header("X-Internal-Token",workerToken).header("Content-Type",type)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(audio.getBytes())).build();
            var response=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(req,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200) {
                String detail="";
                try {detail=json.readTree(response.body()).path("detail").asText();}catch(Exception ignored){}
                String message=switch(detail) {
                    case "ASR_TIMEOUT" -> "Nhận dạng giọng nói quá lâu. Hãy thử bản ghi ngắn hơn hoặc nhập bằng bàn phím.";
                    case "ASR_BUSY" -> "Đang xử lý một bản ghi khác. Vui lòng thử lại sau một chút.";
                    case "ASR_INVALID_AUDIO", "ASR_EMPTY_AUDIO", "ASR_EMPTY" -> "Chưa nghe rõ nội dung. Hãy ghi âm lại hoặc nhập bằng bàn phím.";
                    case "ASR_AUDIO_TOO_LONG" -> "Bản ghi dài quá 60 giây. Hãy ghi âm ngắn hơn.";
                    case "QWEN_ASR_MODEL_NOT_CONFIGURED" -> "Model nhận giọng nói chưa được cấu hình. Bạn vẫn có thể nhập bằng bàn phím.";
                    default -> response.statusCode()==401 ? "Cấu hình kết nối nhận giọng nói chưa khớp. Hãy kiểm tra worker." : "Model nhận giọng nói chưa sẵn sàng. Hãy thử lại hoặc nhập bằng bàn phím.";
                };
                throw new ApiException(ErrorCode.INVALID_REQUEST,message);
            }
            return json.readValue(response.body(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new ApiException(ErrorCode.INVALID_REQUEST,"Đã dừng nhận dạng giọng nói.");}
        catch(ApiException e){throw e;}
        catch(java.net.http.HttpTimeoutException e){throw new ApiException(ErrorCode.INVALID_REQUEST,"Nhận dạng giọng nói quá lâu. Hãy thử bản ghi ngắn hơn hoặc nhập bằng bàn phím.");}
        catch(Exception e){throw new ApiException(ErrorCode.INVALID_REQUEST,"Chưa kết nối được worker nhận giọng nói. Hãy bật worker hoặc nhập bằng bàn phím.");}
    }
}
