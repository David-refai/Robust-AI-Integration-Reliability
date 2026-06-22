package com.chasacademy.airobust.dto;

import java.util.List;

/**
 * Wire-format request body for the OpenAI Chat Completions API.
 *
 * <p>This is intentionally separate from {@link AiResponseDto}: this record
 * describes what WE send to the provider, while {@code AiResponseDto} is the
 * validated application-level schema we expect to get back.
 */
public record OpenAiChatRequest(String model, double temperature, List<Message> messages) {

    public static final String SYSTEM_ROLE = "system";
    public static final String USER_ROLE = "user";

    public record Message(String role, String content) {}
}
