package com.vacancytracker.vacancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class VisaSponsorshipTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "Visa sponsorship is available for this role.",
        "We sponsor work visas and help with relocation.",
        "We offer visa support and a relocation package.",
        "Relocation & visa assistance provided.",
        "We help you with the visa process.",
        "Wir bieten Visa-Sponsoring und Umzugshilfe.",
        "Oferujemy wsparcie wizowe.",
        "Пропонуємо візову підтримку та релокацію.",
        "Визовая поддержка для кандидатов из-за рубежа."})
    void anOfferIsTrue(String text) {
        assertThat(VisaSponsorship.detect("Engineer", text)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "We are unable to offer visa sponsorship.",
        "No visa sponsorship. Candidates must already have the right to work in the UK.",
        "Unfortunately we cannot sponsor visas for this position.",
        "This role does not offer sponsorship.",
        "You must hold a valid work permit for Germany.",
        "Sponsorship is not available for this role, but we offer visa support to family members.",
        "Ми не надаємо візову підтримку."})
    void aRefusalIsFalseAndWinsOverAnOffer(String text) {
        assertThat(VisaSponsorship.detect("Engineer", text)).isFalse();
    }

    @Test
    void silenceIsUnknown() {
        assertThat(VisaSponsorship.detect("Engineer", "Remote role, great team, competitive salary.")).isNull();
        assertThat(VisaSponsorship.detect(null, null)).isNull();
    }

    @Test
    void theVacancyFollowsItsDescription() {
        Vacancy v = Vacancy.discovered(UUID.randomUUID(), "Engineer", "Acme", null, null, "Visa sponsorship available", Instant.now());
        assertThat(v.getVisaSponsorship()).isTrue();

        v.applyAutomated(new AutomatedFields("We cannot sponsor visas", null, null, null, null, Instant.now()));
        assertThat(v.getVisaSponsorship()).isFalse();

        v.editDescription("A plain description");
        assertThat(v.getVisaSponsorship()).isNull();
        assertThat(Vacancy.restore(v.toState()).getVisaSponsorship()).isNull();
    }
}
