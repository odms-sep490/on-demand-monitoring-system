package com.ondemandmonitoring.conversation;

import com.ondemandmonitoring.common.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/customer/order-conversations")
@PreAuthorize("hasRole('CUSTOMER')") @RequiredArgsConstructor
public class OrderConversationController {
    private final OrderConversationService service;
    public record Speech(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=1500) String text) {}
    @PostMapping(value="/{id}/speech", produces="audio/wav")
    public org.springframework.http.ResponseEntity<byte[]> speech(@PathVariable String id, @Valid @RequestBody Speech input) {
        return org.springframework.http.ResponseEntity.ok().header("Cache-Control","no-store").body(service.speech(id,input.text()));
    }
    @PostMapping(value="/transcribe",consumes="multipart/form-data")
    public ApiResponse<java.util.Map<String,Object>> transcribe(@RequestParam("audio") org.springframework.web.multipart.MultipartFile audio) {return ApiResponse.ok(service.transcribe(audio));}
    @PostMapping public ApiResponse<OrderConversationService.View> create() {return ApiResponse.ok(service.create());}
    @GetMapping("/{id}") public ApiResponse<OrderConversationService.View> read(@PathVariable String id) {return ApiResponse.ok(service.read(id));}
    @PostMapping("/{id}/description-review") public ApiResponse<OrderConversationService.View> descriptionReview(@PathVariable String id,@Valid @RequestBody OrderConversationService.DescriptionReview input) {return ApiResponse.ok(service.reviewDescription(id,input));}
    @DeleteMapping("/{id}") public ApiResponse<Void> delete(@PathVariable String id) {service.delete(id);return ApiResponse.ok("Conversation deleted",null);}
    @PostMapping("/{id}/messages") public ApiResponse<OrderConversationService.View> message(@PathVariable String id,@Valid @RequestBody OrderConversationService.Message input) {return ApiResponse.ok(service.message(id,input));}
    @PostMapping("/{id}/confirm") public ApiResponse<OrderConversationService.View> confirm(@PathVariable String id,@Valid @RequestBody OrderConversationService.Revision input) {return ApiResponse.ok(service.confirm(id,input.revision()));}
    @PostMapping("/{id}/validate") public ApiResponse<OrderConversationService.View> validate(@PathVariable String id,@Valid @RequestBody OrderConversationService.Validation input) {return ApiResponse.ok(service.validate(id,input));}
}
