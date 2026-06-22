package com.chasacademy.airobust.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Wire-format response envelope from the OpenAI Chat Completions API.
 *
 * <p>Only the fields needed to reach the assistant's reply text are modeled
 * here; everything else in the real response (usage stats, ids, ...) is
 * ignored on purpose.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiChatResponse(List<Choice> choices) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(Message message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String content) {}
}
