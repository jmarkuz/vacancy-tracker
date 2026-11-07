package com.vacancytracker.application.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MailFilterTest {

    @Test
    void categoriesMayRepeatAndBeCommaListsAndBlankPartsAreSkipped() {
        MailFilter f = MailFilter.parse(null, false, List.of("offer, rejection", "job_alert", " ,"), null, null);

        assertThat(f.categories()).containsExactlyInAnyOrder("offer", "rejection", "job_alert");
    }

    @Test
    void anUnknownCategoryIsRefusedWithTheValidOnes() {
        assertThatThrownBy(() -> MailFilter.parse(null, false, List.of("nope"), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope")
                .hasMessageContaining("job_alert");
        assertThatThrownBy(() -> MailFilter.parse(null, false, List.of("not_job"), null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTextOverTwoHundredCharactersIsRefusedAndABlankOneIsNoFilter() {
        assertThatThrownBy(() -> MailFilter.parse(null, false, null, null, "x".repeat(201))).isInstanceOf(IllegalArgumentException.class);
        assertThat(MailFilter.parse(null, false, null, null, "   ").query()).isNull();
        assertThat(MailFilter.parse(null, false, null, null, "  acme ").query()).isEqualTo("acme");
    }

    @Test
    void likeWildcardsAndTheEscapeCharacterAreEscaped() {
        assertThat(MailFilter.escapeLike("100%_a\\b")).isEqualTo("100\\%\\_a\\\\b");
    }

    @Test
    void theConditionsOnlyContainNamesAndBoundValues() {
        UUID v = UUID.randomUUID();
        MailFilter f = new MailFilter(v, true, java.util.Set.of("offer"), true, "50%'; DROP TABLE x");

        MailFilter.Where w = f.where(true);

        assertThat(w.sql()).doesNotContain("DROP").doesNotContain("50%").contains(":v", ":cats", ":q", "vacancy_id IS NOT NULL", "pending");
        assertThat(w.params()).containsEntry("v", v).containsEntry("q", "%50\\%'; DROP TABLE x%").containsKey("cats");
        assertThat(f.where(false).params()).doesNotContainKey("cats");
        assertThat(MailFilter.of(null, false).where(true).sql()).isEqualTo("TRUE");
    }
}
