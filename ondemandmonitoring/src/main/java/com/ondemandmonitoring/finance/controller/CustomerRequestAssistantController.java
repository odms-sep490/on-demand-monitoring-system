package com.ondemandmonitoring.finance.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.finance.service.impl.QuoteAgentClient;
import com.ondemandmonitoring.pricing.dto.ChecklistPricingPreviewRequests;
import com.ondemandmonitoring.pricing.service.ChecklistPricingPreviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only help while creating a request. No draft order or quote is persisted. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/customer/request-assistant")
@PreAuthorize("hasRole('CUSTOMER')")
public class CustomerRequestAssistantController {
    private final ChecklistPricingPreviewService pricing;
    private final QuoteAgentClient agent;

    public record Message(@NotBlank @Size(max = 1500) String question,
            @NotNull @Valid ChecklistPricingPreviewRequests.Customer selection) {}

    @PostMapping("/messages")
    public ApiResponse<QuoteAgentClient.Answer> ask(@Valid @RequestBody Message message, Authentication authentication) {
        var selection = message.selection();
        if (selection.orderId() != null || selection.items() == null || selection.items().isEmpty()
                || selection.items().stream().anyMatch(item -> item == null || item.existingItemId() != null)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Select monitoring items for a new request");
        }
        var preview = pricing.customerQuote(selection);
        var items = preview.checklists().stream().map(item -> Map.<String, Object>of(
                "name", item.content(), "images", item.requestedImageCount(), "videos", item.requestedVideoCount(),
                "includedImages", item.includedImageCount(), "includedVideos", item.includedVideoCount(),
                "subtotal", item.subtotal(), "needsManagerQuote", item.quotePending())).toList();
        var context = Map.<String, Object>of(
                "service", preview.serviceName(), "currency", preview.currency(), "total", preview.total(),
                "items", items, "addOns", preview.addOns(), "needsManagerQuote", preview.quotePending(),
                "businessRules", List.of(
                    "Đây là bước chọn nội dung khi tạo yêu cầu giám sát bằng drone. Chưa gửi yêu cầu.",
                    "Khách chọn khu vực, nội dung cần giám sát, số ảnh/video mong muốn và thời gian.",
                    "Chi phí đang hiển thị là tạm tính theo lựa chọn hiện tại. Quản lý xác nhận báo giá chính thức sau khi xem yêu cầu.",
                    "Nội dung cần báo giá riêng phải được quản lý xem xét; không coi chi phí chưa tính là miễn phí.",
                    "Sau khi gửi yêu cầu, quản lý xem xét điều kiện và nội dung, chuẩn bị báo giá và hợp đồng cho khách.",
                    "Khách thực hiện xác nhận hợp đồng và thanh toán theo hướng dẫn của đơn hàng; sau đó hệ thống tổ chức nhiệm vụ và bàn giao kết quả.",
                    "Ảnh/video là sản phẩm bàn giao theo nội dung đã chọn. Khách có thể sửa số lượng bằng biểu tượng bút trong từng hạng mục.",
                    "Giảm số ảnh/video trong số lượng đã bao gồm không đồng nghĩa sẽ giảm giá gói.",
                    "AI chỉ giải thích, không tự sửa lựa chọn, gửi yêu cầu, chốt giá, cấp phép hoặc hứa lịch bay."));
        return ApiResponse.ok(agent.explainRequest(message.question(), context, authentication.getName()));
    }
}
