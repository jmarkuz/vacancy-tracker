package com.vacancytracker.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

class CanonicalUrlTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "https://Example.COM/jobs/42/|https://example.com/jobs/42",
            "https://example.com/jobs/42?utm_source=x&utm_medium=y|https://example.com/jobs/42",
            "https://example.com/jobs/42?id=7&gclid=abc&ref=feed|https://example.com/jobs/42?id=7",
            "https://example.com:443/jobs/42#apply|https://example.com/jobs/42",
            "http://example.com:8080/jobs|http://example.com:8080/jobs",
            "https://example.com/|https://example.com/",
            "https://example.com/jobs?UTM_Campaign=z|https://example.com/jobs"
    })
    void stripsTrackingAndNormalizes(String raw, String expected) {
        assertThat(CanonicalUrl.of(raw)).contains(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void blankInputIsEmpty(String raw) {
        assertThat(CanonicalUrl.of(raw)).isEmpty();
    }

    @Test
    void relativeOrInvalidUrlIsEmpty() {
        assertThat(CanonicalUrl.of("/jobs/42")).isEqualTo(Optional.empty());
        assertThat(CanonicalUrl.of("https://exa mple.com")).isEmpty();
    }
}
