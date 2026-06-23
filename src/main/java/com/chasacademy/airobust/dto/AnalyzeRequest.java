package com.chasacademy.airobust.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Incoming request body for {@code POST /api/sentiment/analyze}.
 *
 * <p>The text length is capped to keep prompt size (and cost) bounded; this
 * is validated at the controller boundary, before the AI is ever called.
 */
public record AnalyzeRequest(

    @NotBlank(message = "text must not be blank")
    @Size(max = 4000, message = "text must be at most 4000 characters")
    String text
) {}
