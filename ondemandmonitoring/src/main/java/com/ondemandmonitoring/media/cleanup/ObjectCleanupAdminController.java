package com.ondemandmonitoring.media.cleanup;

import com.ondemandmonitoring.common.api.ApiResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/storage-cleanup")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class ObjectCleanupAdminController {
    private final ObjectCleanupOutbox outbox;

    @GetMapping
    public ApiResponse<ObjectCleanupOutbox.TaskPage> list(
            @RequestParam(defaultValue = "DEAD") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            return ApiResponse.ok(outbox.list(status, page, size));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bộ lọc tác vụ không hợp lệ");
        }
    }

    @PostMapping("/{id}/retry")
    public ApiResponse<Void> retry(@PathVariable UUID id) {
        if (!outbox.retry(id.toString()))
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Chỉ có thể thử lại tác vụ đã hết lượt xử lý");
        return ApiResponse.ok("Đã lên lịch xử lý lại", null);
    }
}
