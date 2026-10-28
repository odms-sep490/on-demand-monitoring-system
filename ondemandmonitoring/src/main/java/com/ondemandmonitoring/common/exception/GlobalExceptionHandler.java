package com.ondemandmonitoring.common.exception;

import com.ondemandmonitoring.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.InterruptedIOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(org.springframework.web.servlet.NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoEndpointFound(
            org.springframework.web.servlet.NoHandlerFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(
                ErrorCode.RESOURCE_NOT_FOUND.name(), ErrorCode.RESOURCE_NOT_FOUND.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadSize(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.error(ErrorCode.INVALID_REQUEST.name(), "Upload exceeds the configured size limit"));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingPart(MissingServletRequestPartException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.VALIDATION_ERROR.name(),
                "Required multipart part is missing: " + exception.getRequestPartName()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(
            AccessDeniedException exception,
            HttpServletRequest request
    ) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        String principal = authentication == null ? "anonymous" : authentication.getName();
        var authorities = authentication == null ? List.<String>of()
                : authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .sorted()
                        .toList();
        // Do not log credentials, tokens, cookies, or request bodies.
        log.warn("Access denied. status=403, method={}, path={}, principal={}, authorities={}, reason={}",
                request.getMethod(), request.getRequestURI(), principal, authorities,
                exception.getClass().getSimpleName());

        return ResponseEntity
                .status(ErrorCode.ACCESS_DENIED.getStatus())
                .body(ApiResponse.error(
                        ErrorCode.ACCESS_DENIED.name(),
                        ErrorCode.ACCESS_DENIED.getMessage()
                ));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApiException(
            ApiException exception
    ) {
        return ResponseEntity
                .status(exception.getStatus())
                .body(ApiResponse.error(
                        exception.getErrorCode().name(),
                        exception.getMessage()
                ));
    }
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingServletRequestParameter(
            MissingServletRequestParameterException exception,
            HttpServletRequest request
    ) {
        log.debug(
                "Missing required request parameter '{}' of type '{}' for {} {}",
                exception.getParameterName(),
                exception.getParameterType(),
                request.getMethod(),
                request.getRequestURI()
        );

        Map<String, String> errors = new LinkedHashMap<>();
        errors.put(
                exception.getParameterName(),
                "Required request parameter is missing"
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(
                        ErrorCode.VALIDATION_ERROR.name(),
                        "Missing required request parameter",
                        errors
                ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(
            MethodArgumentNotValidException exception
    ) {
        Map<String, String> errors = new LinkedHashMap<>();

        for (FieldError fieldError
                : exception.getBindingResult().getFieldErrors()) {
            errors.put(
                    fieldError.getField(),
                    fieldError.getDefaultMessage()
            );
        }

        return ResponseEntity
                .status(ErrorCode.VALIDATION_ERROR.getStatus())
                .body(ApiResponse.error(
                        ErrorCode.VALIDATION_ERROR.name(),
                        ErrorCode.VALIDATION_ERROR.getMessage(),
                        errors
                ));
    }

    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleParameterValidation(jakarta.validation.ConstraintViolationException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getConstraintViolations().forEach(violation ->
                errors.put(violation.getPropertyPath().toString(), violation.getMessage()));
        return ResponseEntity.badRequest().body(ApiResponse.error(
                ErrorCode.VALIDATION_ERROR.name(), "Invalid request parameters", errors));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(
            NoResourceFoundException exception
    ) {
        log.debug(
                "No endpoint found for {} {}",
                exception.getHttpMethod(),
                exception.getResourcePath()
        );

        return ResponseEntity
                .status(ErrorCode.RESOURCE_NOT_FOUND.getStatus())
                .body(ApiResponse.error(
                        ErrorCode.RESOURCE_NOT_FOUND.name(),
                        ErrorCode.RESOURCE_NOT_FOUND.getMessage()
                ));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleConcurrentUpdate(ObjectOptimisticLockingFailureException exception) {
        log.warn("Concurrent resource update rejected: {}", exception.getMessage());
        return ResponseEntity
                .status(ErrorCode.CONCURRENT_UPDATE.getStatus())
                .body(ApiResponse.error(ErrorCode.CONCURRENT_UPDATE.name(), ErrorCode.CONCURRENT_UPDATE.getMessage()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception
    ) {
        log.debug(
                "HTTP method {} is not supported. Supported methods: {}",
                exception.getMethod(),
                exception.getSupportedHttpMethods()
        );

        return ResponseEntity
                .status(ErrorCode.METHOD_NOT_ALLOWED.getStatus())
                .body(ApiResponse.error(
                        ErrorCode.METHOD_NOT_ALLOWED.name(),
                        ErrorCode.METHOD_NOT_ALLOWED.getMessage()
                ));
    }
    @ExceptionHandler(InterruptedIOException.class)
    public ResponseEntity<ApiResponse<Void>> handleInterruptedIOException(
            InterruptedIOException ex,
            HttpServletRequest request
    ) {
        log.warn(
                "Request interrupted: {} {} - {}",
                request.getMethod(),
                request.getRequestURI(),
                ex.getMessage()
        );

        ApiResponse<Void> response = ApiResponse.<Void>builder()
                .code("REQUEST_INTERRUPTED")
                .message("Yêu cầu đã bị gián đoạn")
                .success(false)
                .timestamp(Instant.now())
                .build();

        return ResponseEntity
                .status(HttpStatus.REQUEST_TIMEOUT)
                .body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        if (isClientDisconnect(exception)) {
            log.debug(
                    "Client disconnected while streaming media: {} {}",
                    request.getMethod(),
                    request.getRequestURI()
            );
            return null;
        }

        if (response.isCommitted()) {
            log.warn(
                    "Response already committed, cannot send JSON error response "
                            + "for: {} {}. Error: {}",
                    request.getMethod(),
                    request.getRequestURI(),
                    exception.getMessage()
            );
            return null;
        }

        log.error(
                "Unexpected API error: {} {}",
                request.getMethod(),
                request.getRequestURI(),
                exception
        );

        return ResponseEntity
                .status(ErrorCode.INTERNAL_SERVER_ERROR.getStatus())
                .body(ApiResponse.error(
                        ErrorCode.INTERNAL_SERVER_ERROR.name(),
                        ErrorCode.INTERNAL_SERVER_ERROR.getMessage()
                ));
    }

    private boolean isClientDisconnect(Throwable exception) {
        Throwable cause = exception;

        while (cause != null) {
            String className = cause.getClass().getName();

            String message = cause.getMessage() != null
                    ? cause.getMessage().toLowerCase()
                    : "";

            if (className.contains("ClientAbortException")
                    || message.contains("broken pipe")
                    || message.contains("connection reset by peer")
                    || message.contains(
                    "an established connection was aborted "
                            + "by the software in your host machine"
            )) {
                return true;
            }

            cause = cause.getCause();
        }

        return false;
    }
}
