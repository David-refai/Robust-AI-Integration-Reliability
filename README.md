# Robust AI Integration & Reliability

A Spring Boot service that acts as a **secure, defensive proxy** in front of an external LLM
(OpenAI's Chat Completions API). It does not just forward requests to the model - it wraps every call
in a reliability layer that handles the things LLM APIs are notorious for: hanging connections, rate
limiting, and replies that look like JSON but cannot be trusted.

> Built for *Individuell Labb 1k5: Robust AI-integration & Tillförlitlighet* (ChasAcademy).
> See [`RELIABILITY.md`](RELIABILITY.md) for the written reliability assessment (prompt strategy,
> error mitigation, and an honest critique of the LLM's limitations).

## Why this exists

Calling an LLM API directly from application code and trusting the response is fragile in three
specific ways:

1. **The provider can hang.** Without a timeout, one slow upstream call can tie up a server thread
   indefinitely.
2. **The provider can rate-limit you.** A burst of traffic returns `429 Too Many Requests`, and a
   naive client either crashes or silently drops the request.
3. **The model can hallucinate.** It can return text that is conversational instead of JSON, or JSON
   that is syntactically valid but semantically wrong (an out-of-range score, an invented category).

This project addresses all three at the same layer, so every caller gets the benefit automatically
instead of having to reimplement it per use case.

## Architecture

```
                         POST /api/sentiment/analyze
                                    |
                                    v
                    +-------------------------------+
                    |  SentimentAnalysisController  |   <- HTTP boundary only
                    |  (validates AnalyzeRequest)   |
                    +-------------------------------+
                                    |
                                    v
                    +-------------------------------+
                    |        AiClientService         |   <- the reliability layer
                    |---------------------------------|
                    | 1. Fail-fast config check       |   @PostConstruct, crashes
                    |    (API key present?)           |   on startup if misconfigured
                    |---------------------------------|
                    | 2. Strict timeouts               |   connect 2s / read 8s
                    |    (SimpleClientHttpRequestFactory)
                    |---------------------------------|
                    | 3. Deterministic prompt          |   system+user roles,
                    |    (buildRequestPayload)         |   temperature 0.1
                    |---------------------------------|
                    | 4. Exponential backoff           |   retries 429 up to N times,
                    |    (postWithBackoff)             |   1s -> 2s -> 4s ...
                    |---------------------------------|
                    | 5. Parse + validate + fallback   |   Jackson + Bean Validation,
                    |    (parseAndValidate)            |   never trusts raw AI output
                    +-------------------------------+
                                    |
                                    v
                         External LLM provider
                       (OpenAI-compatible HTTP API)

           Any unrecoverable failure -> AiServiceUnavailableException
                                    |
                                    v
                    +-------------------------------+
                    |     GlobalExceptionHandler      |   <- uniform error responses
                    |  503 (AI unavailable) / 400      |
                    |  (validation failure)            |
                    +-------------------------------+
```

### Package layout

```
com.chasacademy.airobust
├── RobustAiIntegrationApplication.java   Spring Boot entry point
├── client/
│   └── AiClientService.java              The reliability layer (see above)
├── controller/
│   └── SentimentAnalysisController.java  Thin HTTP boundary
├── dto/
│   ├── AnalyzeRequest.java               Validated inbound request
│   ├── AiResponseDto.java                Validated, trusted AI result
│   ├── OpenAiChatRequest.java            Outbound wire format (to the provider)
│   └── OpenAiChatResponse.java           Inbound wire format (from the provider)
└── exception/
    ├── AiServiceUnavailableException.java
    ├── ErrorResponse.java
    └── GlobalExceptionHandler.java       Maps exceptions to HTTP responses
```

The split between `OpenAiChatRequest`/`OpenAiChatResponse` (what we send/receive over the wire) and
`AiResponseDto` (the schema *we* trust internally) is deliberate: the provider's envelope and our
application's contract are different concerns, validated independently.

## Getting started

### Prerequisites

- Java 21
- An API key from an OpenAI-compatible provider

### Configuration

The service reads its configuration from environment variables (see
[`application.properties`](src/main/resources/application.properties)):

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `OPENAI_API_KEY` | **Yes** | _(none)_ | The app refuses to start without this - see Fail-Fast below. |
| `OPENAI_API_URL` | No | `https://api.openai.com/v1/chat/completions` | Override for a different provider or a local mock. |
| `OPENAI_API_MODEL` | No | `gpt-4o-mini` | Which chat model to call. |
| `AI_CONNECT_TIMEOUT_MS` | No | `2000` | Max time to establish a connection. |
| `AI_READ_TIMEOUT_MS` | No | `8000` | Max time to wait for a response. |
| `AI_MAX_RETRIES` | No | `3` | Attempts before giving up on a persistent `429`. |
| `AI_INITIAL_BACKOFF_MS` | No | `1000` | Delay before the first retry; doubles every attempt after. |

### Running the application

```bash
export OPENAI_API_KEY="sk-..."
./mvnw spring-boot:run
```

If `OPENAI_API_KEY` is not set, the application **fails fast on startup**:

```
java.lang.IllegalStateException: CRITICAL: API key is missing.
```

This is intentional: a missing key is a deployment-time configuration error, not something that
should only surface when the first real user request fails.

### Calling the API

```bash
curl -X POST http://localhost:8080/api/sentiment/analyze \
  -H "Content-Type: application/json" \
  -d '{"text": "I absolutely love how fast this shipped!"}'
```

Successful response:

```json
{
  "sentiment": "POSITIVE",
  "confidenceScore": 95,
  "summary": "The user is very happy with the fast shipping."
}
```

If the AI provider is unreachable, times out, or stays rate-limited past the retry budget:

```http
HTTP/1.1 503 Service Unavailable
{"code": "AI_SERVICE_UNAVAILABLE", "message": "AI provider did not respond in time."}
```

If the request body itself is invalid (blank or overlong text):

```http
HTTP/1.1 400 Bad Request
{"code": "VALIDATION_ERROR", "message": "text must not be blank"}
```

## Testing

```bash
./mvnw test
```

The suite is split by concern:

- **`AiClientServiceTest`** - unit tests for the fail-fast configuration check.
- **`SentimentAnalysisControllerTest`** - a `@WebMvcTest` slice covering the HTTP boundary
  (request validation, response shape) with `AiClientService` mocked out.
- **`AiClientServiceEdgeCaseTest`** - the Step 6 stress tests. A [WireMock](https://wiremock.org/)
  server stands in for the real OpenAI endpoint so every failure mode can be triggered
  deterministically and repeatably:
  - a response delayed past the read timeout,
  - `429` twice followed by a success (verifies the backoff loop recovers),
  - `429` on every attempt (verifies retries are exhausted, not infinite),
  - a conversational, non-JSON reply (hallucination),
  - syntactically valid JSON with out-of-range/invalid values (a different kind of hallucination).

This replaces the lab's suggested manual approach (hand-editing timeout values, standing up a
throwaway `429` controller) with something that runs unattended in CI and never touches production
configuration. See [`RELIABILITY.md`](RELIABILITY.md) for the reasoning behind each safety net and an
honest assessment of what it does and does not protect against.

## Tech stack

- Java 21, Spring Boot 3.5 (Web, Validation)
- Spring's `RestClient` for outbound HTTP
- Jackson for JSON (de)serialization
- Jakarta Bean Validation (Hibernate Validator) for response schema enforcement
- JUnit 5, AssertJ, Mockito, WireMock for testing
