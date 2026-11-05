package com.vehiclefinance.api.config;

import com.vehiclefinance.api.model.ApiProblem;
import com.vehiclefinance.api.service.OtpVerificationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiProblem> handleValidation(MethodArgumentNotValidException ex) {
        List<Map<String, String>> errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(this::toFieldError)
                .toList();

        return buildProblem(HttpStatus.BAD_REQUEST, "Validation failed", "One or more fields are invalid.", "VALIDATION_ERROR", errors);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiProblem> handleResponseStatus(ResponseStatusException ex) {
        return buildProblem(HttpStatus.valueOf(ex.getStatusCode().value()), ex.getReason(), ex.getReason(), "HTTP_ERROR", null);
    }

    @ExceptionHandler(OtpVerificationService.OtpRateLimitException.class)
    public ResponseEntity<ApiProblem> handleOtpThrottle(OtpVerificationService.OtpRateLimitException ex) {
        ApiProblem problem = new ApiProblem("OTP request throttled", ex.getMessage(), HttpStatus.TOO_MANY_REQUESTS.value(),
                "OTP_RATE_LIMITED", UUID.randomUUID().toString(), List.of());
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).headers(headers).body(problem);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiProblem> handleConflict(IllegalStateException ex) {
        return buildProblem(HttpStatus.CONFLICT, "Workflow conflict", ex.getMessage(), "WORKFLOW_CONFLICT", null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiProblem> handleBusinessValidation(IllegalArgumentException ex) {
        return buildProblem(HttpStatus.UNPROCESSABLE_CONTENT, "Request could not be processed", ex.getMessage(), "BUSINESS_RULE_VIOLATION", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiProblem> handleGeneric(Exception ex) {
        return buildProblem(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error", "The request could not be processed.", "SERVER_ERROR", null);
    }

    private ResponseEntity<ApiProblem> buildProblem(HttpStatus status, String title, String detail, String code,
                                                   List<Map<String, String>> fieldErrors) {
        ApiProblem problem = new ApiProblem(
                title,
                detail,
                status.value(),
                code,
                UUID.randomUUID().toString(),
                fieldErrors
        );
        return ResponseEntity.status(status).body(problem);
    }

    private Map<String, String> toFieldError(FieldError error) {
        return Map.of(
                "field", error.getField(),
                "code", error.getCode(),
                "message", error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage()
        );
    }
}
