package com.vacancytracker.collection.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AdapterDatesTest {

    private static final Instant T = Instant.parse("2026-10-30T10:00:00Z");

    @Test
    void anEpochInSecondsOrMillisecondsGivesTheSameMoment() {
        assertThat(AdapterDates.fromEpoch(T.getEpochSecond())).isEqualTo(T);
        assertThat(AdapterDates.fromEpoch(T.toEpochMilli())).isEqualTo(T);
        assertThat(AdapterDates.fromEpoch(0)).isNull();
        assertThat(AdapterDates.fromEpoch(-5)).isNull();
    }

    @Test
    void textInTheCommonFormatsIsRead() {
        assertThat(AdapterDates.instantOrNull("2026-10-30T10:00:00Z")).isEqualTo(T);
        assertThat(AdapterDates.instantOrNull("2026-10-30T12:00:00+02:00")).isEqualTo(T);
        assertThat(AdapterDates.instantOrNull("2026-10-30 10:00:00")).isEqualTo(T);
        assertThat(AdapterDates.instantOrNull("2026-10-30")).isEqualTo(Instant.parse("2026-10-30T00:00:00Z"));
        assertThat(AdapterDates.instantOrNull("Fri, 30 Oct 2026 10:00:00 GMT")).isEqualTo(T);
        assertThat(AdapterDates.instantOrNull(String.valueOf(T.getEpochSecond()))).isEqualTo(T);
        assertThat(AdapterDates.instantOrNull("last week")).isNull();
    }

    @Test
    void aJsonFieldMayBeANumberOrText() throws Exception {
        var json = new ObjectMapper().readTree("{\"a\": " + T.toEpochMilli() + ", \"b\": \"2026-10-30T12:00:00+02:00\", \"c\": null}");

        assertThat(AdapterDates.fromNode(json.path("a"))).isEqualTo(T);
        assertThat(AdapterDates.fromNode(json.path("b"))).isEqualTo(T);
        assertThat(AdapterDates.fromNode(json.path("c"))).isNull();
        assertThat(AdapterDates.fromNode(json.path("missing"))).isNull();
    }
}
