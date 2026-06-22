package com.chasacademy.airobust;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// A dummy API key is supplied here because AiClientService fails fast on
// startup when it is missing (see Step 1) - this confirms the rest of the
// application context still wires up correctly once configuration is valid.
@SpringBootTest(properties = "openai.api.key=test-key-for-context-loading")
class RobustAiIntegrationApplicationTests {

	@Test
	void contextLoads() {
	}

}
