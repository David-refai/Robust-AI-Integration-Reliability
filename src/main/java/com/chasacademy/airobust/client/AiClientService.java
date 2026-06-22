package com.chasacademy.airobust.client;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

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

    @Value("${openai.api.url}")
    private String apiUrl;

    /** Max time to establish a TCP connection to the provider. */
    @Value("${ai.client.connect-timeout-ms}")
    private int connectTimeoutMs;

    /** Max time to wait for a response once connected. LLM calls are slow, but never unbounded. */
    @Value("${ai.client.read-timeout-ms}")
    private int readTimeoutMs;

    private RestClient restClient;

    @PostConstruct
    void init() {
        validateConfiguration();
        this.restClient = buildRestClient();
    }

    /**
     * Fail-fast startup check.
     *
     * <p>Booting with a missing or blank API key is a critical misconfiguration:
     * every request would fail anyway, but only after a real (and possibly slow)
     * network round-trip. Crashing immediately on startup turns a confusing
     * runtime failure into an obvious deployment-time one.
     */
    void validateConfiguration() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("CRITICAL: API key is missing.");
        }
    }

    /**
     * Builds a {@link RestClient} bound to strict connect/read timeouts.
     *
     * <p>Without an upper bound, a hung AI provider would tie up a server
     * thread indefinitely. {@link SimpleClientHttpRequestFactory} enforces
     * both a connect timeout (failing fast if the host is unreachable) and a
     * read timeout (failing fast if the host accepts the connection but never
     * replies), so every call has a strict, predictable worst-case latency.
     */
    private RestClient buildRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);

        return RestClient.builder()
            .baseUrl(apiUrl)
            .requestFactory(requestFactory)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();
    }
}
