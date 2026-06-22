package com.chasacademy.airobust.exception;

/**
 * Thrown when the AI provider cannot be reached, times out, keeps rate
 * limiting us past the retry budget, or replies with a payload that does not
 * even match the wire-format envelope we expect.
 *
 * <p>This is a runtime exception by design: callers should not be forced to
 * declare it everywhere, and {@code GlobalExceptionHandler} turns it into a
 * clean 503 response instead of letting it crash the request thread.
 */
public class AiServiceUnavailableException extends RuntimeException {

    public AiServiceUnavailableException(String message) {
        super(message);
    }

    public AiServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
