package com.chasacademy.airobust.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Our application's validated schema for an AI sentiment-analysis result.
 *
 * <p>The AI is instructed (see {@code AiClientService}'s system prompt) to
 * return exactly this shape, but instructions alone are not a safety net.
 * Bean Validation enforces the contract at the boundary so a syntactically
 * valid but semantically wrong reply - e.g. a {@code confidenceScore} of
 * 150, or a made-up sentiment like "HAPPY" - never reaches the rest of the
 * application.
 */
public record AiResponseDto(

    @NotNull
    @Pattern(regexp = "POSITIVE|NEUTRAL|NEGATIVE", message = "sentiment must be POSITIVE, NEUTRAL or NEGATIVE")
    String sentiment,

    @NotNull
    @Min(0)
    @Max(100)
    Integer confidenceScore,

    @NotBlank
    @Size(max = 200)
    String summary
) {

    /**
     * Safe default returned whenever the AI's reply cannot be trusted -
     * either because it was not valid JSON, or because it failed validation.
     * Callers always get a well-formed DTO back, never an exception caused
     * by a bad AI reply.
     */
    public static AiResponseDto fallback() {
        return new AiResponseDto("NEUTRAL", 0, "Unable to reliably analyze this content.");
    }
}
