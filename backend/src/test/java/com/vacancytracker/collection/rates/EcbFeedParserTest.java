package com.vacancytracker.collection.rates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vacancytracker.support.TestHttp;
import java.io.IOException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class EcbFeedParserTest {

    @Test
    void readsTheDateAndEveryCurrency() throws IOException {
        var parsed = EcbFeedParser.parse(TestHttp.fixture(EcbFeedParserTest.class, "ecb-sample.xml"));

        assertThat(parsed.date()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(parsed.perEur()).containsOnlyKeys("USD", "JPY", "GBP", "PLN");
        assertThat(parsed.perEur().get("USD")).isEqualByComparingTo("1.1172");
        assertThat(parsed.perEur().get("GBP")).isEqualByComparingTo("0.83380");
    }

    @Test
    void skipsAnUnreadableRateButKeepsTheOthers() {
        String xml = "<Cube><Cube time='2026-10-30'><Cube currency='USD' rate='abc'/><Cube currency='PLN' rate='4.4'/>"
                + "<Cube currency='JPY' rate='0'/></Cube></Cube>";

        assertThat(EcbFeedParser.parse(xml).perEur()).containsOnlyKeys("PLN");
    }

    @Test
    void rejectsDocumentsThatAreNotARatesFeed() {
        assertThatThrownBy(() -> EcbFeedParser.parse("<html>Not a feed</html>"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no dated rates");
        assertThatThrownBy(() -> EcbFeedParser.parse("<Cube><Cube time='yesterday'><Cube currency='USD' rate='1'/></Cube></Cube>"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("invalid date");
        assertThatThrownBy(() -> EcbFeedParser.parse("<Cube><Cube time='2026-10-30'></Cube></Cube>"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no usable rates");
    }
}
