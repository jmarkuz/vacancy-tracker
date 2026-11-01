package com.vacancytracker.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.vacancytracker.common.LogMasking;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.json.JsonWriter;

/**
 * The JSON log format masks every text value (T-072). The running configuration is only chosen once per JVM, so the
 * customizer is checked on its own here, and the whole vps console output was checked by running the packaged app.
 */
class MaskingJsonCustomizerTest {

    @AfterEach
    void forget() {
        LogMasking.clearRegistered();
    }

    @Test
    void everyTextMemberIsMaskedAndOtherMembersAreKept() {
        LogMasking.register(List.of("ACCESS-TOKEN-0123456789abcdef"));
        Logger logger = new LoggerContext().getLogger("test.json");
        LoggingEvent event = new LoggingEvent("x", logger, Level.WARN, "Failed ACCESS-TOKEN-0123456789abcdef", null, null);
        JsonWriter<ILoggingEvent> writer = JsonWriter.<ILoggingEvent>of(members -> {
            members.add("message", ILoggingEvent::getFormattedMessage);
            members.add("url", e -> "https://x.test/?app_key=SECRETVALUE");
            members.add("level", e -> e.getLevel().toString());
            members.add("count", e -> 7);
            new MaskingJsonCustomizer().customize(members);
        });

        String json = writer.writeToString(event);

        assertThat(json).contains("\"message\":\"Failed ***\"").contains("app_key=***").contains("\"level\":\"WARN\"").contains("\"count\":7");
        assertThat(json).doesNotContain("ACCESS-TOKEN").doesNotContain("SECRETVALUE");
    }
}
