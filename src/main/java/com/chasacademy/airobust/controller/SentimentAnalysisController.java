package com.chasacademy.airobust.controller;

import com.chasacademy.airobust.client.AiClientService;
import com.chasacademy.airobust.dto.AiResponseDto;
import com.chasacademy.airobust.dto.AnalyzeRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public entry point to the AI-backed sentiment analysis feature.
 *
 * <p>This controller is intentionally thin: all reliability concerns
 * (timeouts, retries, prompt construction, validation/fallback) live in
 * {@link AiClientService}, so this class only deals with HTTP plumbing.
 */
@RestController
@RequestMapping("/api/sentiment")
public class SentimentAnalysisController {

    private final AiClientService aiClientService;

    public SentimentAnalysisController(AiClientService aiClientService) {
        this.aiClientService = aiClientService;
    }

    @PostMapping("/analyze")
    public AiResponseDto analyze(@Valid @RequestBody AnalyzeRequest request) {
        return aiClientService.analyzeSentiment(request.text());
    }
}
