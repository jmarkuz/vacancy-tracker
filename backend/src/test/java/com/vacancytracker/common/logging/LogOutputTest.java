package com.vacancytracker.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vacancytracker.collection.http.HttpFetcher;
import com.vacancytracker.collection.http.UpstreamException;
import com.vacancytracker.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The real console output of the running configuration, with secrets set through the same properties the
 * deployment uses (T-072). No secret value may appear in it, whichever way it gets into a message.
 */
@SpringBootTest(properties = {
        "app.sources.adzuna.app-key=ADZUNA-KEY-0123456789",
        "app.sources.himalayas.api-key=HIMALAYAS-KEY-abcdef",
        "app.mail.imap.password=imap-p4ssw0rd-xyz",
        "app.notifications.telegram.bot-token=987654:TELEGRAM-SECRET-token",
        "app.access-token=ACCESS-TOKEN-0123456789abcdef"})
@ExtendWith(OutputCaptureExtension.class)
class LogOutputTest extends AbstractIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger("test.masking");
    private static final String[] SECRETS = {"ADZUNA-KEY-0123456789", "HIMALAYAS-KEY-abcdef", "imap-p4ssw0rd-xyz",
            "TELEGRAM-SECRET-token", "ACCESS-TOKEN-0123456789abcdef"};

    @Autowired
    private HttpFetcher fetcher;

    private static void assertNoSecrets(CapturedOutput output) {
        for (String secret : SECRETS) {
            assertThat(output.getAll()).as("secret %s in the log", secret).doesNotContain(secret);
        }
    }

    @Test
    void anAddressWithAKeyIsMaskedInAMessage(CapturedOutput output) {
        LOG.warn("Calling https://api.adzuna.test/v1/gb/search/1?app_id=public&app_key=ADZUNA-KEY-0123456789&what=java");

        assertThat(output.getAll()).contains("app_key=***&what=java");
        assertNoSecrets(output);
    }

    @Test
    void aConfiguredSecretIsMaskedEvenInFreeText(CapturedOutput output) {
        LOG.error("IMAP login rejected for the password imap-p4ssw0rd-xyz and the token ACCESS-TOKEN-0123456789abcdef");

        assertThat(output.getAll()).contains("IMAP login rejected for the password *** and the token ***");
        assertNoSecrets(output);
    }

    @Test
    void aStackTraceWhoseMessageHoldsASecretIsMasked(CapturedOutput output) {
        LOG.error("Request failed", new IllegalStateException("bad url https://x.test/?app_key=ADZUNA-KEY-0123456789 and HIMALAYAS-KEY-abcdef"));

        assertThat(output.getAll()).contains("IllegalStateException: bad url https://x.test/?app_key=*** and ***");
        assertNoSecrets(output);
    }

    @Test
    void aTelegramAddressIsMasked(CapturedOutput output) {
        LOG.warn("POST https://api.telegram.org/bot987654:TELEGRAM-SECRET-token/sendMessage failed");

        assertThat(output.getAll()).contains("/bot***/sendMessage");
        assertNoSecrets(output);
    }

    @Test
    void aBadAddressInTheFetcherDoesNotLeakTheKeyThroughTheExceptionOrItsLog(CapturedOutput output) {
        String url = "https://api.adzuna.test/search?what=data engineer&app_key=ADZUNA-KEY-0123456789";

        assertThatThrownBy(() -> fetcher.get(url, "cache-key", java.util.Map.of()))
                .isInstanceOf(UpstreamException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("ADZUNA-KEY-0123456789"));
        try {
            fetcher.get(url, "cache-key", java.util.Map.of());
        } catch (UpstreamException e) {
            LOG.error("Source failed", e);
        }

        assertNoSecrets(output);
    }

    @Test
    void anUpstreamErrorMessageThatQuotesAnAddressIsMasked() {
        UpstreamException e = new UpstreamException("Network error after 3 attempts: https://x.test/?app_key=ADZUNA-KEY-0123456789");

        assertThat(e.getMessage()).isEqualTo("Network error after 3 attempts: https://x.test/?app_key=***");
    }
}
