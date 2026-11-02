package com.vacancytracker.collection.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import com.vacancytracker.collection.adapter.RawPosting;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NormalizerTest {

    private static RawPosting raw(String title, String company, String location, String workMode, String description,
                                  BigDecimal min, BigDecimal max, String currency, String period) {
        return new RawPosting("test", "1", "https://ex.com/jobs/1?utm_source=feed", title, company, location,
                workMode, description, min, max, currency, period, null);
    }

    @Test
    void detectsTheCountryOnlyWhenTheLocationNamesOne() {
        assertThat(Normalizer.normalize(raw("Dev", "Acme", "Berlin, Germany", null, null, null, null, null, null))
                .orElseThrow().country()).isEqualTo("DE");
        assertThat(Normalizer.normalize(raw("Dev", "Acme", "Remote", null, null, null, null, null, null))
                .orElseThrow().country()).isNull();
    }

    @Test
    void cleansHtmlAndCollapsesWhitespace() {
        NormalizedPosting n = Normalizer.normalize(raw("  Backend   Engineer ", " Acme ", " Berlin ", null,
                "<p>Build <b>APIs</b>\n\n  daily.</p><ul><li>Java</li></ul>", null, null, null, null)).orElseThrow();

        assertThat(n.title()).isEqualTo("Backend Engineer");
        assertThat(n.company()).isEqualTo("Acme");
        assertThat(n.location()).isEqualTo("Berlin");
        assertThat(n.description()).isEqualTo("Build APIs daily. Java");
        assertThat(n.canonicalUrl()).isEqualTo("https://ex.com/jobs/1");
    }

    @Test
    void rejectsPostingsWithoutTitleOrCompany() {
        assertThat(Normalizer.normalize(raw("  ", "Acme", null, null, null, null, null, null, null))).isEmpty();
        assertThat(Normalizer.normalize(raw("Dev", null, null, null, null, null, null, null, null))).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "Remote, EU, remote",
            "Hybrid - remote friendly, Berlin, hybrid",
            "Berlin (on-site), , onsite",
            "Warsaw, , ",
    })
    void detectsWorkModeFromLocation(String location, String description, String expected) {
        NormalizedPosting n = Normalizer.normalize(raw("Dev", "Acme", location, null, description, null, null, null, null))
                .orElseThrow();
        assertThat(n.workMode()).isEqualTo(expected == null || expected.isEmpty() ? null : expected);
    }

    @Test
    void explicitWorkModeFromSourceWins() {
        NormalizedPosting n = Normalizer.normalize(raw("Dev", "Acme", "Remote", "hybrid", null, null, null, null, null))
                .orElseThrow();
        assertThat(n.workMode()).isEqualTo("hybrid");
    }

    @Test
    void salaryWithoutCurrencyIsDropped() {
        NormalizedPosting n = Normalizer.normalize(raw("Dev", "Acme", null, null, null,
                BigDecimal.valueOf(50000), null, null, "year")).orElseThrow();
        assertThat(n.salaryMin()).isNull();
        assertThat(n.currency()).isNull();
        assertThat(n.salaryPeriod()).isNull();
    }

    @Test
    void reversedSalaryRangeIsSwappedAndNegativeIsIgnored() {
        NormalizedPosting n = Normalizer.normalize(raw("Dev", "Acme", null, null, null,
                BigDecimal.valueOf(90000), BigDecimal.valueOf(70000), "eur", "year")).orElseThrow();
        assertThat(n.salaryMin()).isEqualByComparingTo("70000");
        assertThat(n.salaryMax()).isEqualByComparingTo("90000");
        assertThat(n.currency()).isEqualTo("EUR");

        NormalizedPosting negative = Normalizer.normalize(raw("Dev", "Acme", null, null, null,
                BigDecimal.valueOf(-5), null, "EUR", "year")).orElseThrow();
        assertThat(negative.salaryMin()).isNull();
    }

    @Test
    void emptyHtmlBecomesNullNotEmptyString() {
        assertThat(Normalizer.normalize(raw("Dev", "Acme", null, null, "<p>  </p>", null, null, null, null))
                .orElseThrow().description()).isNull();
        assertThat(Optional.ofNullable(Normalizer.normalize(raw("Dev", "Acme", null, null, null, null, null, null, null))
                .orElseThrow().location())).isEmpty();
    }
}
