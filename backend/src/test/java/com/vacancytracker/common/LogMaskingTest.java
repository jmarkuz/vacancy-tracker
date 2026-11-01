package com.vacancytracker.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LogMaskingTest {

    @AfterEach
    void forget() {
        LogMasking.clearRegistered();
    }

    @Test
    void keysInAQueryStringAreMaskedWhateverTheirName() {
        assertThat(LogMasking.mask("GET https://api.example.test/search?what=java&app_id=abc123&app_key=SECRETVALUE&page=2"))
                .isEqualTo("GET https://api.example.test/search?what=java&app_id=***&app_key=***&page=2");
        assertThat(LogMasking.mask("https://x.test/?api_key=K1&apikey=K2&token=K3&access_token=K4&secret=K5&password=K6&key=K7&sig=K8"))
                .isEqualTo("https://x.test/?api_key=***&apikey=***&token=***&access_token=***&secret=***&password=***&key=***&sig=***");
        assertThat(LogMasking.mask("failed: (https://x.test/a?Key=Upper)")).isEqualTo("failed: (https://x.test/a?Key=***)");
    }

    @Test
    void otherQueryParametersAreLeftAlone() {
        String text = "https://x.test/jobs?what=data+engineer&where=Prague&salary_min=50000&keyword=kotlin";

        assertThat(LogMasking.mask(text)).isEqualTo(text);
    }

    @Test
    void bearerAndAuthorizationValuesAreMasked() {
        assertThat(LogMasking.mask("header Authorization: Bearer abcdef1234567890")).isEqualTo("header Authorization: Bearer ***");
        assertThat(LogMasking.mask("sent Bearer abcdef1234567890 to server")).isEqualTo("sent Bearer *** to server");
        assertThat(LogMasking.mask("Authorization=Basic dXNlcjpwYXNz")).isEqualTo("Authorization=Basic ***");
    }

    @Test
    void passwordsInAddressesAndTheSessionCookieAreMasked() {
        assertThat(LogMasking.mask("connect https://user:p4ss@host.test/path")).isEqualTo("connect https://***@host.test/path");
        assertThat(LogMasking.mask("Cookie: a=1; vt_token=the-token-value; b=2")).isEqualTo("Cookie: a=1; vt_token=***; b=2");
    }

    @Test
    void telegramBotAddressesAndJoobleKeysAreMasked() {
        assertThat(LogMasking.mask("POST https://api.telegram.org/bot123456:AAE-secret_token/sendMessage"))
                .isEqualTo("POST https://api.telegram.org/bot***/sendMessage");
        assertThat(LogMasking.mask("GET https://jooble.org/api/0a1b2c3d-key?x=1")).isEqualTo("GET https://jooble.org/api/***?x=1");
    }

    @Test
    void registeredSecretsAreMaskedAnywhereInTheText() {
        LogMasking.register(List.of("hunter2hunter2", "  padded-secret-1  ", "short", "", "   "));

        assertThat(LogMasking.mask("login failed for hunter2hunter2 on host")).isEqualTo("login failed for *** on host");
        assertThat(LogMasking.mask("value=padded-secret-1.")).isEqualTo("value=***.");
        assertThat(LogMasking.mask("the word short stays")).isEqualTo("the word short stays");
    }

    @Test
    void aSecretThatContainsAnotherIsHiddenAsAWhole() {
        LogMasking.register(List.of("abcdef", "abcdef-extended-secret"));

        assertThat(LogMasking.mask("x abcdef-extended-secret y")).isEqualTo("x *** y");
    }

    @Test
    void nullAndEmptyPassThrough() {
        assertThat(LogMasking.mask(null)).isNull();
        assertThat(LogMasking.mask("")).isEmpty();
        assertThat(LogMasking.mask("nothing secret here")).isEqualTo("nothing secret here");
    }

    @Test
    void maskingTwiceChangesNothingFurther() {
        String once = LogMasking.mask("https://x.test/?app_key=SECRETVALUE");

        assertThat(LogMasking.mask(once)).isEqualTo(once);
    }
}
