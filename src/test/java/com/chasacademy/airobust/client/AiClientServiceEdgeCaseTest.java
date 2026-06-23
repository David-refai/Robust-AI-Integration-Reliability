package com.chasacademy.airobust.client;

import com.chasacademy.airobust.dto.AiResponseDto;
import com.chasacademy.airobust.exception.AiServiceUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Step 6 of the lab: deliberately triggering every failure mode the
 * reliability layer is supposed to defend against, against a WireMock
 * stand-in for the real OpenAI endpoint.
 *
 * <p>A mocked HTTP server is used instead of manually flipping timeout
 * values or standing up a throwaway controller (as the lab text suggests),
 * since that approach is repeatable, runs in CI, and does not require
 * hand-editing production config to prove the edge cases work.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "openai.api.key=test-key",
        "ai.client.connect-timeout-ms=300",
        "ai.client.read-timeout-ms=1000",
        "ai.client.max-retries=3",
        "ai.client.initial-backoff-ms=20"
    }
)
class AiClientServiceEdgeCaseTest {

    private static final String ENDPOINT_PATH = "/v1/chat/completions";

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    @DynamicPropertySource
    static void pointAiClientAtWireMock(DynamicPropertyRegistry registry) {
        registry.add("openai.api.url", () -> wireMock.baseUrl() + ENDPOINT_PATH);
    }

    @Autowired
    private AiClientService aiClientService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
    }

    @Test
    void forcedTimeout_throwsAiServiceUnavailableInsteadOfHanging() {
        // The stub delays its response well past the 1000ms read timeout configured above.
        wireMock.stubFor(post(urlEqualTo(ENDPOINT_PATH))
            .willReturn(aResponse().withStatus(200).withFixedDelay(2000)));

        assertThatThrownBy(() -> aiClientService.analyzeSentiment("This call should time out."))
            .isInstanceOf(AiServiceUnavailableException.class)
            .hasMessageContaining("did not respond in time");
    }

    @Test
    void forcedAuthFailure_reportsRealStatusInsteadOfTimeoutAndDoesNotRetry() {
        // A 401 means the provider answered and rejected the request (e.g. a bad/missing
        // API key) - a fundamentally different problem from "no response arrived at all",
        // and one retrying will never fix.
        wireMock.stubFor(post(urlEqualTo(ENDPOINT_PATH)).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> aiClientService.analyzeSentiment("Anything"))
            .isInstanceOf(AiServiceUnavailableException.class)
            .hasMessageContaining("HTTP 401")
            .hasMessageNotContaining("did not respond in time");

        wireMock.verify(1, postRequestedFor(urlEqualTo(ENDPOINT_PATH)));
    }

    @Test
    void forced429_retriesWithBackoffThenSucceeds() throws Exception {
        // First two attempts are rate-limited; the third (still within maxRetries) succeeds.
        wireMock.stubFor(post(urlEqualTo(ENDPOINT_PATH))
            .inScenario("rate-limit-then-success")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(429))
            .willSetStateTo("retry-1"));

        wireMock.stubFor(post(urlEqualTo(ENDPOINT_PATH))
            .inScenario("rate-limit-then-success")
            .whenScenarioStateIs("retry-1")
            .willReturn(aResponse().withStatus(429))
            .willSetStateTo("retry-2"));

        wireMock.stubFor(post(urlEqualTo(ENDPOINT_PATH))
            .inScenario("rate-limit-then-success")
            .whenScenarioStateIs("retry-2")
            .willReturn(okJson(openAiEnvelope(
                "{\"sentiment\":\"POSITIVE\",\"confidenceScore\":95,\"summary\":\"The user is happy.\"}"))));

        AiResponseDto result = aiClientService.analyzeSentiment("I love this!");

        assertThat(result.sentiment()).isEqualTo("POSITIVE");
        assertThat(result.confidenceScore()).isEqualTo(95);
        wireMock.verify(3, postRequestedFor(urlEqualTo(ENDPOINT_PATH)));
    }

    @Test
    void forced429_givesUpAfterExhaustingRetries() {
        // Every attempt is rate-limited, so the retry budget is fully spent.
        wireMock.stubFor(post(urlEqualTo(ENDPOINT_PATH)).willReturn(aResponse().withStatus(429)));

        assertThatThrownBy(() -> aiClientService.analyzeSentiment("Anything"))
            .isInstanceOf(AiServiceUnavailableException.class)
            .hasMessageContaining("rate-limited");

        wireMock.verify(3, postRequestedFor(urlEqualTo(ENDPOINT_PATH)));
    }

    @Test
    void forcedHallucination_nonJsonReplyFallsBackToSafeDefault() throws Exception {
        // The model ignores its instructions and replies with conversational text instead of JSON.
        wireMock.stubFor(post(urlEqualTo(ENDPOINT_PATH))
            .willReturn(okJson(openAiEnvelope("Sure, here is your summary of the text..."))));

        AiResponseDto result = aiClientService.analyzeSentiment("Irrelevant input");

        assertThat(result).isEqualTo(AiResponseDto.fallback());
    }

    @Test
    void forcedHallucination_validJsonButInvalidValuesFallsBackToSafeDefault() throws Exception {
        // Syntactically valid JSON, but "HAPPY" is not an allowed sentiment and 150 is out of range.
        wireMock.stubFor(post(urlEqualTo(ENDPOINT_PATH))
            .willReturn(okJson(openAiEnvelope(
                "{\"sentiment\":\"HAPPY\",\"confidenceScore\":150,\"summary\":\"Looks fine syntactically.\"}"))));

        AiResponseDto result = aiClientService.analyzeSentiment("Irrelevant input");

        assertThat(result).isEqualTo(AiResponseDto.fallback());
    }

    /** Wraps a (possibly malformed) assistant reply in a valid OpenAI response envelope. */
    private String openAiEnvelope(String assistantContent) throws Exception {
        Map<String, Object> envelope = Map.of(
            "choices", List.of(Map.of("message", Map.of("content", assistantContent)))
        );
        return objectMapper.writeValueAsString(envelope);
    }
}
