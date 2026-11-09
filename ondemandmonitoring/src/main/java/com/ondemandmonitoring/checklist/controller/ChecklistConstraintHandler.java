package com.ondemandmonitoring.checklist.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.common.exception.ErrorCode;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import lombok.extern.slf4j.Slf4j;

@RestControllerAdvice(basePackages = "com.ondemandmonitoring.checklist.controller")
@Order(-1)
@Slf4j
public class ChecklistConstraintHandler {
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handle(DataIntegrityViolationException exception) {
        ErrorCode code = ErrorCode.INTERNAL_SERVER_ERROR;
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                if ("uk_checklist_normalized_content".equals(violation.getConstraintName())) {
                    code = ErrorCode.CHECKLIST_ALREADY_EXISTS;
                } else if ("uk_service_checklist".equals(violation.getConstraintName())) {
                    code = ErrorCode.SERVICE_CHECKLIST_ALREADY_EXISTS;
                }
                break;
            }
        }
        if (code == ErrorCode.INTERNAL_SERVER_ERROR) {
            log.error("Unexpected checklist integrity violation", exception);
        }
        return ResponseEntity.status(code.getStatus()).body(ApiResponse.error(code.name(), code.getMessage()));
    }
}
