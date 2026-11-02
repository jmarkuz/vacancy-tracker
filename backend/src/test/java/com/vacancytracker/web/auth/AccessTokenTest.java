package com.vacancytracker.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AccessTokenTest {

    private static final String TOKEN = "correct-horse-battery-staple";

    @Test
    void matchesOnlyTheExactToken() {
        AccessToken token = new AccessToken(TOKEN);

        assertThat(token.matches(TOKEN)).isTrue();
        assertThat(token.matches(TOKEN + "x")).isFalse();
        assertThat(token.matches(TOKEN.substring(1))).isFalse();
        assertThat(token.matches(TOKEN.toUpperCase())).isFalse();
        assertThat(token.matches("")).isFalse();
        assertThat(token.matches(null)).isFalse();
    }

    @Test
    void aMissingOrShortTokenStopsTheStartup() {
        assertThatThrownBy(() -> new AccessToken(null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_ACCESS_TOKEN");
        assertThatThrownBy(() -> new AccessToken("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AccessToken("short")).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("short");
        assertThat(new AccessToken("x".repeat(AccessToken.MIN_LENGTH)).matches("x".repeat(AccessToken.MIN_LENGTH))).isTrue();
    }
}
