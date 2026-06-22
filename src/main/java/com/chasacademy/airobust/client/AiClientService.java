package com.chasacademy.airobust.client;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Secure proxy to the external LLM provider (OpenAI-compatible Chat Completions API).
 *
 * <p>This class is the "reliability layer" for the lab: on top of plain HTTP
 * integration it adds fail-fast configuration checks, strict timeouts,
 * deterministic prompting, retry/backoff for rate limits, and response
 * validation. Each concern is added incrementally; see the class-level
 * sections below as the service grows.
 */
@Service
public class AiClientService {

    /**
     * Loaded from the OPENAI_API_KEY environment variable (see application.properties).
     * Never logged and never hardcoded - this is the only place the raw key is held in memory.
     */
    @Value("${openai.api.key}")
    private String apiKey;

    /**
     * Fail-fast startup check.
     *
     * <p>Booting with a missing or blank API key is a critical misconfiguration:
     * every request would fail anyway, but only after a real (and possibly slow)
     * network round-trip. Crashing immediately on startup turns a confusing
     * runtime failure into an obvious deployment-time one.
     */
    @PostConstruct
    void validateConfiguration() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("CRITICAL: API key is missing.");
        }
    }
}
