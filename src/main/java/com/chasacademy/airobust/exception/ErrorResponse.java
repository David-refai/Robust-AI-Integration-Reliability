package com.chasacademy.airobust.exception;

/**
 * Uniform error body returned by {@link GlobalExceptionHandler}.
 *
 * @param code    a stable, machine-readable error identifier
 * @param message a human-readable explanation, safe to show to a client
 */
public record ErrorResponse(String code, String message) {}
