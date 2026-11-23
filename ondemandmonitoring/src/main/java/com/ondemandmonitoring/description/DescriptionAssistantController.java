package com.ondemandmonitoring.description;
import com.ondemandmonitoring.common.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/customer/description-assistant")
@PreAuthorize("hasRole('CUSTOMER')") @RequiredArgsConstructor
public class DescriptionAssistantController {
    private final DescriptionAssistantService service;
    @PostMapping("/generate") public ApiResponse<DescriptionAssistantService.View> generate(@Valid @RequestBody DescriptionAssistantService.Input input) {return ApiResponse.ok(service.generate(input));}
    @GetMapping("/{id}") public ApiResponse<DescriptionAssistantService.View> read(@PathVariable String id) {return ApiResponse.ok(service.read(id));}
    @PostMapping("/{id}/confirm") public ApiResponse<DescriptionAssistantService.View> confirm(@PathVariable String id,@Valid @RequestBody DescriptionAssistantService.Confirmation input) {return ApiResponse.ok(service.confirm(id,input));}
}
