package com.assessment.account.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationErrors(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid value",
                        (a, b) -> a
                ));

        Map<String, Object> body = new HashMap<>();
        body.put("error", "Validation failed");
        body.put("details", fieldErrors);
        body.put("timestamp", Instant.now().toString());

        log.warn("Validation error: {}", fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        Map<String, Object> body = Map.of(
                "error", "Malformed request body",
                "details", ex.getMostSpecificCause().getMessage(),
                "timestamp", Instant.now().toString()
        );
        log.warn("Malformed request: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericError(Exception ex) {
        Map<String, Object> body = Map.of(
                "error", "Internal Server Error",
                "timestamp", Instant.now().toString()
        );
        log.error("Unexpected error", ex);
        return ResponseEntity.internalServerError().body(body);
    }
}
