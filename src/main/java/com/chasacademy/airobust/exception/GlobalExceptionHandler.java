package com.chasacademy.airobust.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Translates internal failures into clean, predictable HTTP responses.
 *
 * <p>Without this, an {@link AiServiceUnavailableException} (a timeout, an
 * exhausted retry budget, or a malformed provider response) would surface as
 * a raw 500 with a stack trace. Centralizing the mapping here keeps that
 * translation in one place instead of scattering try/catch blocks across
 * every controller.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * The AI provider is unreachable, timed out, or stayed rate-limited past
     * our retry budget. 503 tells the client this is a temporary upstream
     * problem, not something wrong with their request.
     */
    @ExceptionHandler(AiServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleAiServiceUnavailable(AiServiceUnavailableException ex) {
        // Logged with the full cause chain (not just ex.getMessage()) so anyone
        // running this service - including someone testing it without a real,
        // working API key - can see the *actual* root exception (e.g. a 401 from
        // the provider) in the server console, not just the generic client-facing
        // message below.
        log.error("AI service unavailable: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(new ErrorResponse("AI_SERVICE_UNAVAILABLE", ex.getMessage()));
    }

    /**
     * Request body failed Bean Validation (e.g. blank or too-long text)
     * before the AI was ever called.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationFailure(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(new ErrorResponse("VALIDATION_ERROR", message));
    }
}
