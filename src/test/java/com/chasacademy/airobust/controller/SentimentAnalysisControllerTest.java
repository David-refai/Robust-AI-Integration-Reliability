package com.chasacademy.airobust.controller;

import com.chasacademy.airobust.client.AiClientService;
import com.chasacademy.airobust.dto.AiResponseDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test for the HTTP boundary: request validation and response shape.
 * AiClientService is mocked here - its own reliability behaviour (timeouts,
 * backoff, fallback) is covered separately in AiClientServiceEdgeCaseTest.
 */
@WebMvcTest(SentimentAnalysisController.class)
class SentimentAnalysisControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AiClientService aiClientService;

    @Test
    void returnsAnalysisResultForValidRequest() throws Exception {
        when(aiClientService.analyzeSentiment(any()))
            .thenReturn(new AiResponseDto("POSITIVE", 92, "The user is happy with the product."));

        mockMvc.perform(post("/api/sentiment/analyze")
                .contentType("application/json")
                .content("{\"text\": \"I love this product!\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sentiment").value("POSITIVE"))
            .andExpect(jsonPath("$.confidenceScore").value(92));
    }

    @Test
    void rejectsBlankTextWithBadRequest() throws Exception {
        mockMvc.perform(post("/api/sentiment/analyze")
                .contentType("application/json")
                .content("{\"text\": \"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void rejectsOverlongTextWithBadRequest() throws Exception {
        String tooLong = "a".repeat(4001);

        mockMvc.perform(post("/api/sentiment/analyze")
                .contentType("application/json")
                .content("{\"text\": \"" + tooLong + "\"}"))
            .andExpect(status().isBadRequest());
    }
}
