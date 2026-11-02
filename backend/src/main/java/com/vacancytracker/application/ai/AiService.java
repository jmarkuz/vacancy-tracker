package com.vacancytracker.application.ai;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The one door to Claude (T-082, ADR-041). AI is off by default: app.ai.provider is "off" unless the owner sets
 * cli, api or fake, and even then nothing is sent until the owner switches AI on in the app, which records a consent
 * for that provider. A consent for one provider does not cover another, and it can be withdrawn at any time.
 */
@Service
public class AiService {

    private static final Logger LOG = LoggerFactory.getLogger(AiService.class);

    /** What the owner agrees to, shown next to the switch. */
    public static final String NOTICE = "When you use AI, Anthropic (Claude) receives the vacancy you chose, the preset you picked "
            + "(its main goal, the weights of its parameters and the document prompt), your base resume and base cover letter, and "
            + "any instructions you add, to adapt your base document to the vacancy. Nothing is sent automatically: only when you "
            + "press Generate with AI. Use as is sends nothing. Mail is never sent. You can switch AI off at any time.";

    public record Status(String provider, boolean configured, boolean consented, boolean enabled, Instant consentedAt, String notice) {
    }

    private final List<AiClient> clients;
    private final String provider;
    private final JdbcClient jdbc;
    private final Clock clock;

    public AiService(List<AiClient> clients, @Value("${app.ai.provider:off}") String provider, JdbcClient jdbc, Clock clock) {
        this.clients = List.copyOf(clients);
        this.provider = provider == null ? "off" : provider.trim().toLowerCase(Locale.ROOT);
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private Optional<AiClient> client() {
        return clients.stream().filter(c -> c.provider().equals(provider)).findFirst();
    }

    public Status status() {
        boolean configured = client().map(AiClient::configured).orElse(false);
        Optional<Instant> consent = consentFor(provider);
        return new Status(provider, configured, consent.isPresent(), configured && consent.isPresent(), consent.orElse(null), NOTICE);
    }

    /** Records the owner's opt-in for the configured provider. Only possible when that provider is usable. */
    public Status consent() {
        if (client().map(AiClient::configured).orElse(false)) {
            jdbc.sql("INSERT INTO ai_consent (provider, granted_at) VALUES (:p, :at)"
                            + " ON CONFLICT (provider) DO UPDATE SET granted_at = EXCLUDED.granted_at")
                    .param("p", provider)
                    .param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                    .update();
            LOG.info("AI switched on by the owner for provider {}", provider);
            return status();
        }
        throw new AiUnavailableException(unavailableReason());
    }

    public Status revoke() {
        jdbc.sql("DELETE FROM ai_consent").update();
        LOG.info("AI switched off by the owner");
        return status();
    }

    /** Sends the prompt, or refuses before anything leaves the machine when AI is not on. */
    public AiClient.AiReply complete(AiClient.AiPrompt prompt) {
        Status status = status();
        if (!status.enabled()) {
            throw new AiUnavailableException(status.configured()
                    ? "AI is off. Switch it on in Settings first: nothing is sent to Claude before you do."
                    : unavailableReason());
        }
        return client().orElseThrow().complete(prompt);
    }

    private String unavailableReason() {
        return switch (provider) {
            case "off" -> "AI is not configured. Set APP_AI_PROVIDER to cli (Claude Code under your subscription) or api "
                    + "(ANTHROPIC_API_KEY), then switch AI on in Settings.";
            case "api" -> "AI uses the Claude API, but ANTHROPIC_API_KEY is not set.";
            case "cli" -> "AI uses the Claude CLI, but APP_AI_CLI_COMMAND is empty.";
            default -> "Unknown APP_AI_PROVIDER '" + provider + "': use off, cli or api.";
        };
    }

    private Optional<Instant> consentFor(String p) {
        return jdbc.sql("SELECT granted_at FROM ai_consent WHERE provider = :p")
                .param("p", p)
                .query((rs, n) -> rs.getObject(1, OffsetDateTime.class).toInstant())
                .optional();
    }
}
