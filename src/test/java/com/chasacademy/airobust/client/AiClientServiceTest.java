package com.chasacademy.airobust.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Unit tests for the fail-fast configuration check described in Step 1 of the lab.
 */
class AiClientServiceTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void startupFailsFastWhenApiKeyIsMissingOrBlank(String invalidKey) {
        AiClientService service = new AiClientService();
        ReflectionTestUtils.setField(service, "apiKey", invalidKey);

        assertThatIllegalStateException()
            .isThrownBy(service::validateConfiguration)
            .withMessage("CRITICAL: API key is missing.");
    }

    @Test
    void startupSucceedsWhenApiKeyIsPresent() {
        AiClientService service = new AiClientService();
        ReflectionTestUtils.setField(service, "apiKey", "sk-test-key");

        service.validateConfiguration();
        // No exception means the fail-fast guard correctly let a valid key through.
    }
}
