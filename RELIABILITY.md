# Reliability Assessment

This document is the written evaluation required for VG on *Individuell Labb 1k5*. It covers the
prompt strategy, the error-mitigation approach, and an honest assessment of where the AI integration
is still fragile and how the architecture compensates for that.

## 1. Prompt Strategy

The system prompt (see [`AiClientService.SYSTEM_PROMPT`](src/main/java/com/chasacademy/airobust/client/AiClientService.java))
turns the model into a single-purpose JSON extractor rather than a general-purpose chat assistant.
Three deliberate choices went into it:

- **The schema is spelled out field-by-field**, with explicit types and allowed values
  (`"POSITIVE" | "NEUTRAL" | "NEGATIVE"`, an integer range, a length-bounded string). Vague
  instructions like "return JSON" reliably produce inconsistent shapes; an exact schema in the
  prompt gives the model far less room to improvise.
- **Markdown fences and conversational text are explicitly forbidden.** Left to their default
  behavior, chat-tuned models wrap JSON in ` ```json ` fences and add a friendly preamble
  ("Sure, here's the analysis:"). Both would break a naive `JSON.parse`/`ObjectMapper.readValue`
  call, so the prompt forbids them outright.
- **The user's text is framed as data, not instructions**, and the prompt tells the model to ignore
  any request embedded in that text that tries to change its role or reveal the system prompt. This
  is a first line of defense against prompt injection - it does not make injection impossible, but it
  meaningfully raises the bar, and it costs nothing to include.

JSON format is enforced **structurally**, not just through wording: `temperature` is fixed at `0.1`
(see `AiClientService.TEMPERATURE`) to bias the model toward the most likely (and therefore most
repeatable) completion instead of a creative one, and the user's text is sent in its own `"user"` role
message rather than concatenated into the system prompt, which keeps the trust boundary between
"instructions" and "data" visible to the model's own role-handling.

The chosen task - sentiment analysis with a confidence score and a one-line summary - was picked
because it is simple enough to validate exhaustively with Bean Validation, while still being
representative of the more general problem this lab is about: getting structured, trustworthy output
from a fundamentally text-in/text-out system.

## 2. Error Mitigation

Three independent failure modes are handled, each at the layer where it naturally occurs:

| Failure mode | Where it's caught | Mechanism |
|---|---|---|
| Provider hangs or is slow | `AiClientService.postWithBackoff` | Strict `connect`/`read` timeouts on the `RestClient`'s `SimpleClientHttpRequestFactory` (2s / 8s by default). A timeout surfaces as `RestClientException`/`ResourceAccessException`, wrapped into `AiServiceUnavailableException` - never a hung server thread. |
| `429 Too Many Requests` | `AiClientService.postWithBackoff` | Caught specifically as `HttpClientErrorException.TooManyRequests`, retried up to `ai.client.max-retries` (default 3) times with exponential backoff (`Thread.sleep`, starting at 1000ms and doubling). If every attempt is rate-limited, the failure is wrapped into `AiServiceUnavailableException` rather than thrown raw. |
| Hallucinated / malformed reply | `AiClientService.parseAndValidate` | Two safety nets: (1) `ObjectMapper.readValue` catches replies that are not valid JSON at all; (2) Jakarta Bean Validation (`@NotNull`, `@Pattern`, `@Min`, `@Max`, `@NotBlank`, `@Size` on `AiResponseDto`) catches replies that parse fine but violate the business rules - an invented sentiment value, an out-of-range score. Either failure returns `AiResponseDto.fallback()` instead of propagating bad data. |

A fourth failure mode - missing configuration - is treated even more strictly: `AiClientService`
fails **fast**, at startup, via `@PostConstruct`, if the API key is missing or blank. A misconfigured
deployment should never get far enough to make a single request; it should fail loudly the moment it
starts, not silently on the first real user request.

All three runtime failure modes converge on a single exception type, `AiServiceUnavailableException`,
which `GlobalExceptionHandler` maps to a `503 Service Unavailable` with a clean JSON error body. The
caller of `POST /api/sentiment/analyze` never sees a raw stack trace, regardless of which of these
three things went wrong upstream.

These behaviors are exercised automatically, not just asserted in this document:
[`AiClientServiceEdgeCaseTest`](src/test/java/com/chasacademy/airobust/client/AiClientServiceEdgeCaseTest.java)
points `AiClientService` at a WireMock server standing in for OpenAI and deliberately forces each
scenario above - a delayed response past the read timeout, a `429` that resolves after two retries, a
`429` that never resolves, a conversational non-JSON reply, and a syntactically valid but
rule-violating reply - and asserts on the resulting behavior. This was chosen over the lab's suggested
approach of manually editing timeout values or standing up a throwaway controller, because a mocked
HTTP layer is repeatable, runs unattended in CI, and never requires hand-editing production
configuration to prove the safety nets work.

## 3. Reliability Assessment - An Honest Critique

It would be easy to overstate how "solved" this makes AI reliability. It doesn't. What this
architecture buys is *containment*, not *correctness*. A few specific limitations worth being explicit
about:

- **The model can still be confidently wrong.** Bean Validation only catches replies that are
  *malformed* (wrong shape, out-of-range values, invented enum values). It cannot catch a reply that
  is syntactically perfect and still the wrong answer - a `confidenceScore` of 90 attached to the
  wrong `sentiment`, for instance. No amount of schema validation distinguishes a confident mistake
  from a confident correct answer. In a production setting, this means the validation layer should be
  read as "this response is well-formed enough to use," not "this response is correct."
- **Determinism is biased, not guaranteed.** `temperature: 0.1` makes repeat calls *more likely* to
  agree, not certain to. Providers also reserve the right to change model versions and weights behind
  a fixed API string, so the exact same input can legitimately produce a different result next month
  even with identical code. Anything downstream that assumes byte-for-byte reproducibility (caching
  keyed on input text alone, golden-file tests asserting exact output) will be brittle. This is also
  why the fallback path matters as much as the happy path: it is the part of the system that has to be
  reproducible.
- **Retrying 429s assumes the rate limit is transient and the request is idempotent.** Both are true
  for a stateless sentiment-analysis call, which is why exponential backoff is the right tool here.
  It would be the *wrong* default for a request with side effects (e.g. "charge the user," "send the
  email") without an idempotency key, since a retried request could double-apply the side effect. The
  backoff logic in this codebase is intentionally scoped to read-only AI calls.
- **A timeout budget is a tradeoff, not a fix.** An 8-second read timeout protects the server from a
  hanging upstream call, but it also means a provider that is merely *slow* (9 seconds on a complex
  prompt, say) is treated identically to one that is *broken*. The "right" timeout is a business
  decision, not a technical one, and 8 seconds here is a reasonable default for a synchronous web
  request, not a universally correct number.
- **The prompt-injection defense is a deterrent, not a guarantee.** Framing user text as "data, not
  instructions" raises the cost of a successful injection attack; it does not make one impossible
  against a sufficiently adversarial input. A production system handling untrusted input at scale
  would need additional layers - output-side filtering, a second model call to classify the first
  model's output, or a non-LLM rules engine for anything security-sensitive - rather than relying on
  prompt wording alone.
- **None of this protects against a provider-side outage that returns `200 OK` with garbage, or a
  silent pricing/policy change.** This lab's safety nets all assume the HTTP layer itself is honest
  about success vs. failure. A provider that returns `200` with an empty or truncated body due to an
  internal error would be caught by this codebase's JSON/validation fallback (the reply simply
  wouldn't parse or validate) - which is a fortunate side effect of defensive parsing, not something
  specifically designed for.

**Why this is still the right architecture, despite those limitations:** every one of the failure
modes above degrades into a *known, handled state* - a fallback DTO, a `503`, or an immediate startup
crash - rather than an unhandled exception, a hung thread, or silently corrupted data flowing into the
rest of the application. That is the realistic bar for an AI integration: not eliminating the model's
unreliability (which is not possible from the client side), but making sure that unreliability never
escapes as undefined behavior.
