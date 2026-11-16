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

    @Test
    void htmlEntitiesInTitleCompanyAndLocationAreDecoded() {
        NormalizedPosting n = Normalizer.normalize(raw("Developer &amp; Analyst &#8211; Remote", "Marrow &amp; Co", "Prague &amp; Brno",
                null, null, null, null, null, null)).orElseThrow();

        assertThat(n.title()).isEqualTo("Developer & Analyst \u2013 Remote");
        assertThat(n.company()).isEqualTo("Marrow & Co");
        assertThat(n.location()).isEqualTo("Prague & Brno");
        assertThat(Normalizer.normalize(raw("R&D Lead", "AT&T", null, null, null, null, null, null, null)).orElseThrow().title())
                .isEqualTo("R&D Lead");
    }

    @Test
    void aSourceFieldThatNamesNoModeFallsBackToTheText() {
        assertThat(Normalizer.normalize(raw("Dev", "Acme", "Remote - EU", "unspecified", null, null, null, null, null))
                .orElseThrow().workMode()).isEqualTo("remote");
        assertThat(Normalizer.normalize(raw("Dev", "Acme", "Berlin", "", null, null, null, null, null))
                .orElseThrow().workMode()).isNull();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Senior Java Developer (Remote)|Berlin||remote",
            "Java Developer - Hybrid|Berlin||hybrid",
            "Remote Java Developer|Berlin||remote",
            "Hybrid Cloud Architect|Berlin||",
            "Remote Sensing Engineer|Berlin||",
            "Dev|Anywhere||remote",
            "Dev|Worldwide||remote",
            "Dev|Remote|We offer a hybrid model|remote",
            "Dev|Praha / Vzdáleně||remote",
            "Dev|Київ, віддалено||remote",
            "Dev|Москва, удалённо||remote",
            "Dev|Berlin|Homeoffice possible|remote",
            "Dev|Brno|Hybridní model|hybrid",
            "Dev|Berlin|This is not a remote position||",
            "Dev|Berlin|Our hybrid cloud platform||",
    })
    void readsTheModeFromTitleAndOtherLanguages(String title, String location, String description, String expected) {
        NormalizedPosting n = Normalizer.normalize(raw(title, "Acme", location, null, description, null, null, null, null))
                .orElseThrow();
        assertThat(n.workMode()).isEqualTo(expected == null || expected.isEmpty() ? null : expected);
    }

    @Test
    void aZeroAmountMeansNotGiven() {
        NormalizedPosting n = Normalizer.normalize(raw("Dev", "Acme", null, null, null,
                BigDecimal.ZERO, BigDecimal.valueOf(90000), "EUR", "year")).orElseThrow();
        assertThat(n.salaryMin()).isEqualByComparingTo("90000");
        assertThat(n.salaryMax()).isEqualByComparingTo("90000");

        NormalizedPosting none = Normalizer.normalize(raw("Dev", "Acme", null, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, "EUR", "year")).orElseThrow();
        assertThat(none.salaryMin()).isNull();
        assertThat(none.currency()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"€, EUR", "$, USD", "£, GBP", " czk , CZK", "Euro, ", "US dollars, "})
    void currencyIsAnIsoCodeOrDropsTheSalary(String given, String expected) {
        NormalizedPosting n = Normalizer.normalize(raw("Dev", "Acme", null, null, null,
                BigDecimal.valueOf(50000), BigDecimal.valueOf(60000), given, "year")).orElseThrow();
        assertThat(n.currency()).isEqualTo(expected == null || expected.isEmpty() ? null : expected.trim());
        assertThat(n.salaryMin() != null).isEqualTo(expected != null && !expected.isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"year, year", "Yearly, year", "annual, year", "per-year-salary, year", "per year, year", "Monthly, month",
            "per-month-salary, month", "hourly, hour", "per-hour-wage, hour", "weekly, week", "daily, day", "fortnight, "})
    void periodIsOneOfTheKnownWords(String given, String expected) {
        NormalizedPosting n = Normalizer.normalize(raw("Dev", "Acme", null, null, null,
                BigDecimal.valueOf(50), BigDecimal.valueOf(60), "USD", given)).orElseThrow();
        assertThat(n.salaryPeriod()).isEqualTo(expected == null || expected.isEmpty() ? null : expected);
        assertThat(n.salaryMin()).isEqualByComparingTo("50");
    }

    @ParameterizedTest
    @CsvSource({"gb, Leeds, GB", "DE, Remote, DE", "Germany, , DE", "UK, London, GB", "EU, 'Berlin, Germany', DE", "EU, Berlin, ",
            ", Leeds, "})
    void aCountryTheSourceStatesApartFromTheLocationIsUsed(String country, String location, String expected) {
        RawPosting posting = new RawPosting("test", "1", "https://ex.com/jobs/1", "Dev", "Acme", location, null, null,
                null, null, null, null, null, country);

        String detected = Normalizer.normalize(posting).orElseThrow().country();

        assertThat(detected).isEqualTo(expected == null || expected.isEmpty() ? null : expected);
    }
}
