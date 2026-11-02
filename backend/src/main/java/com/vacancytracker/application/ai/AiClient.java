package com.vacancytracker.application.ai;

/**
 * One way to ask Claude for a text (T-082, ADR-041). Implementations: the official CLI under the owner's subscription
 * ({@link ClaudeCliClient}), the Claude API with an API key ({@link AnthropicApiClient}), and a fixed fake for tests
 * ({@link FakeAiClient}). Callers go through {@link AiService}, which refuses to call any of them before the owner's
 * explicit opt-in.
 */
public interface AiClient {

    /** The value of app.ai.provider this client serves: cli, api or fake. */
    String provider();

    /** Whether it can be called at all (a CLI command or an API key is configured). Says nothing about consent. */
    boolean configured();

    /** Asks once. Throws {@link AiFailedException} with a message for the owner when the answer is not usable. */
    AiReply complete(AiPrompt prompt);

    /** system holds the rules, user the data (resume, vacancy) and the task. */
    record AiPrompt(String system, String user, int maxTokens) {
    }

    /** model is what answered, when known. */
    record AiReply(String text, String provider, String model) {
    }
}
