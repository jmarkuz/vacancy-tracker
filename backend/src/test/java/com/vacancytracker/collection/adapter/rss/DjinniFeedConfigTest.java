package com.vacancytracker.collection.adapter.rss;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The Djinni feed from the owner's list ships configured, with its terms check recorded (T-089, docs/sources.md). */
@SpringBootTest(classes = DjinniFeedConfigTest.Config.class, properties = "APP_SOURCES_DJINNI_ENABLED=true")
class DjinniFeedConfigTest {

    @org.springframework.boot.context.properties.EnableConfigurationProperties(RssSourcesProperties.class)
    static class Config {
    }

    @Autowired
    private RssSourcesProperties properties;

    @Test
    void theDjinniFeedIsApprovedWithItsTermsCheck() {
        RssFeed djinni = properties.feeds().stream().filter(f -> f.id().equals("djinni")).findFirst().orElseThrow();

        assertThat(djinni.approved()).isTrue();
        assertThat(djinni.url()).isEqualTo("https://djinni.co/jobs/rss/");
        assertThat(djinni.termsCheckedOn()).isEqualTo("2026-11-02");
        assertThat(djinni.termsUrl()).isEqualTo("https://djinni.co/terms-of-use");
        assertThat(djinni.companyName()).isEqualTo("Djinni");
    }
}
