package com.vacancytracker.application.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.MessageCreateParams;
import java.time.Duration;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Claude through the Claude API with the owner's API key (ANTHROPIC_API_KEY), using the official Java SDK (T-082,
 * ADR-041). Model claude-opus-5-5 by default, effort set explicitly. Server-side fallbacks are on ("default"), so a
 * request a safety classifier declines is retried on a suitable model by the API itself; a refusal that remains is
 * reported, never shown as a document. The key is read from configuration only, sent only to the API, and masked in
 * logs like every property named *key (ADR-034).
 */
@Component
public class AnthropicApiClient implements AiClient {

    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final BetaOutputConfig.Effort effort;
    private volatile AnthropicClient client;

    public AnthropicApiClient(@Value("${app.ai.api.key:${ANTHROPIC_API_KEY:}}") String apiKey,
                              @Value("${app.ai.api.model:claude-opus-5-5}") String model,
                              @Value("${app.ai.api.base-url:}") String baseUrl,
                              @Value("${app.ai.api.effort:medium}") String effort) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.effort = BetaOutputConfig.Effort.of(effort.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    public String provider() {
        return "api";
    }

    @Override
    public boolean configured() {
        return !apiKey.isEmpty();
    }

    private AnthropicClient client() {
        AnthropicClient c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(Duration.ofMinutes(5));
                    if (!baseUrl.isEmpty()) {
                        builder.baseUrl(baseUrl);
                    }
                    c = builder.build();
                    client = c;
                }
            }
        }
        return c;
    }

    @Override
    public AiReply complete(AiPrompt prompt) {
        if (!configured()) {
            throw new AiUnavailableException("No API key: set ANTHROPIC_API_KEY.");
        }
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(prompt.maxTokens())
                .system(prompt.system())
                .outputConfig(BetaOutputConfig.builder().effort(effort).build())
                .addBeta(FALLBACK_BETA)
                .fallbacksDefault()
                .addUserMessage(prompt.user())
                .build();
        BetaMessage message;
        try {
            message = client().beta().messages().create(params);
        } catch (UnauthorizedException | PermissionDeniedException e) {
            throw new AiFailedException("The Claude API refused the API key. Check ANTHROPIC_API_KEY.");
        } catch (RateLimitException e) {
            throw new AiFailedException("The Claude API is rate limiting this key. Try again in a minute.");
        } catch (AnthropicServiceException e) {
            throw new AiFailedException("The Claude API answered HTTP " + e.statusCode() + ". Try again later.");
        } catch (AnthropicIoException e) {
            throw new AiFailedException("Could not reach the Claude API: " + e.getMessage());
        }
        BetaStopReason stop = message.stopReason().orElse(null);
        if (BetaStopReason.REFUSAL.equals(stop)) {
            throw new AiFailedException("Claude declined this request. Edit the instructions or write the document by hand.");
        }
        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining("\n"))
                .strip();
        if (text.isEmpty()) {
            throw new AiFailedException("Claude returned no text.");
        }
        if (BetaStopReason.MAX_TOKENS.equals(stop)) {
            text = text + "\n\n[The answer was cut at the length limit. Check the end before saving.]";
        }
        return new AiReply(text, provider(), message.model().asString());
    }
}
