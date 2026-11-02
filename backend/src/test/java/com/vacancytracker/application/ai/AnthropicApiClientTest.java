package com.vacancytracker.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.vacancytracker.application.ai.AiClient.AiPrompt;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The API adapter against a local server that answers like the Messages API; nothing reaches Anthropic. */
class AnthropicApiClientTest {

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> beta = new AtomicReference<>();
    private final AtomicReference<String> key = new AtomicReference<>();

    private String serve(int status, String answer) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            beta.set(ex.getRequestHeaders().getFirst("anthropic-beta"));
            // SDK 3.x sends the key as a bearer token; older versions used x-api-key.
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            key.set(auth != null ? auth.replaceFirst("^Bearer ", "") : ex.getRequestHeaders().getFirst("x-api-key"));
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static String message(String stopReason, String text) {
        return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}],\"stop_reason\":\"" + stopReason + "\","
                + "\"stop_sequence\":null,\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}";
    }

    @Test
    void sendsTheDefaultModelEffortFallbacksAndKeyAndReturnsTheText() throws IOException {
        String base = serve(200, message("end_turn", "# Tailored resume"));
        AnthropicApiClient api = new AnthropicApiClient("sk-ant-test-key", "claude-opus-5-5", base, "medium");

        AiClient.AiReply reply = api.complete(new AiPrompt("Rules", "Data", 8000));

        assertThat(reply.text()).isEqualTo("# Tailored resume");
        assertThat(reply.provider()).isEqualTo("api");
        assertThat(reply.model()).isEqualTo("claude-opus-5-5");
        assertThat(key.get()).isEqualTo("sk-ant-test-key");
        assertThat(beta.get()).contains(AnthropicApiClient.FALLBACK_BETA);
        assertThat(body.get()).contains("\"model\":\"claude-opus-5-5\"").contains("\"fallbacks\":\"default\"")
                .contains("\"effort\":\"medium\"").contains("\"system\":\"Rules\"").contains("\"max_tokens\":8000");
    }

    @Test
    void aRefusalIsReportedNotReturnedAsADocument() throws IOException {
        String base = serve(200, message("refusal", ""));

        assertThatThrownBy(() -> new AnthropicApiClient("k", "claude-opus-5-5", base, "medium").complete(new AiPrompt("s", "u", 100)))
                .isInstanceOf(AiFailedException.class).hasMessageContaining("declined");
    }

    @Test
    void aCutAnswerSaysSo() throws IOException {
        String base = serve(200, message("max_tokens", "Partial"));

        assertThat(new AnthropicApiClient("k", "claude-opus-5-5", base, "medium").complete(new AiPrompt("s", "u", 100)).text())
                .startsWith("Partial").contains("cut at the length limit");
    }

    @Test
    void aRejectedKeyIsExplainedWithoutTheKey() throws IOException {
        String base = serve(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}");

        assertThatThrownBy(() -> new AnthropicApiClient("sk-ant-secret", "claude-opus-5-5", base, "medium").complete(new AiPrompt("s", "u", 100)))
                .isInstanceOf(AiFailedException.class)
                .hasMessageContaining("refused the API key")
                .hasMessageNotContaining("sk-ant-secret");
    }

    @Test
    void withoutAKeyItIsNotConfiguredAndSendsNothing() {
        AnthropicApiClient api = new AnthropicApiClient("", "claude-opus-5-5", "", "medium");

        assertThat(api.configured()).isFalse();
        assertThatThrownBy(() -> api.complete(new AiPrompt("s", "u", 100))).isInstanceOf(AiUnavailableException.class);
    }
}
