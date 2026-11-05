package com.vacancytracker.application.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The date of an interview or a deadline is read from mail text; anything unclear gives nothing. Mail received Thu 5 Nov 2026, 09:00 UTC. */
class MailEventExtractorTest {

    private static final Instant RECEIVED = Instant.parse("2026-11-05T09:00:00Z");

    private static Arguments found(String text, String start, boolean zoneKnown) {
        return Arguments.of(text, start, zoneKnown);
    }

    static Stream<Arguments> events() {
        return Stream.of(
                found("We would like to invite you to an interview on Thursday, 12 November at 14:30.", "2026-11-12T14:30", false),
                found("Interview: 12.11.2026 о 14:30, Zoom", "2026-11-12T14:30", false),
                found("Your call is on Nov 12, 2026 2:30 PM", "2026-11-12T14:30", false),
                found("Your interview: November 12th, 2026, 10:00 AM", "2026-11-12T10:00", false),
                found("Interview scheduled for 2026-11-12T14:30:00", "2026-11-12T14:30", false),
                found("Interview scheduled for 2026-11-12 14:30", "2026-11-12T14:30", false),
                found("Interview at 14:30 on 12 November 2026", "2026-11-12T14:30", false),
                found("Запрошуємо на співбесіду 12 листопада о 14:30", "2026-11-12T14:30", false),
                found("Співбесіда відбудеться 20 грудня 2026 року о 11:00", "2026-12-20T11:00", false),
                found("Собеседование 12 ноября в 15:00", "2026-11-12T15:00", false),
                found("Let's talk tomorrow at 10:00 about the interview", "2026-11-06T10:00", false),
                found("Інтерв'ю завтра о 10:00", "2026-11-06T10:00", false),
                found("Інтерв'ю післязавтра о 16:15", "2026-11-07T16:15", false),
                found("Interview today at 15:00", "2026-11-05T15:00", false),
                found("Interview next Tuesday at 11:00", "2026-11-10T11:00", false),
                found("Interview on Monday 16:00", "2026-11-09T16:00", false),
                found("Співбесіда у вівторок о 12:00", "2026-11-10T12:00", false),
                found("Interview on Thursday at 10:00", "2026-11-12T10:00", false),
                found("Interview on 12 Nov at 3 pm", "2026-11-12T15:00", false),
                found("Interview on Nov 12 at 9:30 a.m.", "2026-11-12T09:30", false),
                found("Interview 25/11/2026 at 14:30", "2026-11-25T14:30", false),
                found("Interview 11/25/2026 at 14:30", "2026-11-25T14:30", false),
                found("Interview in January: 10 January at 11:00", "2027-01-10T11:00", false),
                found("Interview 12 November 2026 at 14:30 CET", "2026-11-12T13:30", true),
                found("Interview 12 November 2026 at 14:30 (EET)", "2026-11-12T12:30", true),
                found("Interview Nov 12, 2026 at 9:00 AM PST", "2026-11-12T17:00", true),
                found("Interview Nov 12, 2026 at 9:00 AM EDT", "2026-11-12T13:00", true),
                found("Interview 2026-11-12T14:30:00Z", "2026-11-12T14:30", true),
                found("Interview 2026-11-12T14:30:00+02:00", "2026-11-12T12:30", true),
                found("Interview 2026-11-12T14:30:00-05:00", "2026-11-12T19:30", true),
                found("Interview 2026-11-12 14:30 +0100", "2026-11-12T13:30", true),
                found("Interview 12 November 2026 at 14:30 UTC+3", "2026-11-12T11:30", true),
                found("Interview 12 November 2026 at 14:30 GMT+05:30", "2026-11-12T09:00", true),
                found("Interview 12 November 2026 at 14:30 UTC", "2026-11-12T14:30", true),
                found("Interview 12 November 2026 at 14:30 Kyiv time", "2026-11-12T14:30", false),
                found("Hello,\n\nThe test task is due Friday, 13 November 2026 at 23:59.\n\nBest", "2026-11-13T23:59", false),
                found("Please submit by 13 November 2026 18:00 CET", "2026-11-13T17:00", true),
                found("Interview slot:\n12 November 2026\n14:30", "2026-11-12T14:30", false));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("events")
    void findsTheEvent(String text, String start, boolean zoneKnown) {
        Optional<MailEventExtractor.Found> found = MailEventExtractor.extract(text, RECEIVED);

        assertThat(found).isPresent();
        assertThat(found.get().start()).isEqualTo(LocalDateTime.parse(start));
        assertThat(found.get().zoneKnown()).isEqualTo(zoneKnown);
        assertThat(found.get().text()).isNotBlank();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "Thank you for applying, we will get back to you.",
            "",
            "Interview on 12 November.",
            "Interview on Thursday, 12 November",
            "Interview on 4 November 2026 at 10:00",
            "Interview on 12.10.2026 at 10:00",
            "Interview at 4 November at 10:00",
            "Interview on 12 November 2030 at 10:00",
            "Interview on 12 November 2026 at 03:00",
            "Interview on 31 February 2026 at 10:00",
            "Interview on 11/12/2026 at 14:30",
            "Update to version 1.2.3 released 14:30",
            "Upgrade to v1.12.2026 at 10:00",
            "The salary is 3.500 or $12.11 per hour, call 0 800 123 456",
            "Phone +380 44 123 45 67, office hours 9:00-18:00",
            "Our address: 12 Main Street, Kyiv",
            "Interview at 14:30",
            "Interview on 12 November 2026 at 13:00 pm",
            "Sent: 5 Nov 2026 09:00",
            "Interview on Tuesday 10 Nov at 10:00 or Wednesday 11 Nov at 14:00"})
    void givesNothingWhenUnsureOrImplausible(String text) {
        assertThat(MailEventExtractor.extract(text, RECEIVED)).isEmpty();
    }

    @Test
    void aNullTextOrReceivedDateGivesNothing() {
        assertThat(MailEventExtractor.extract(null, RECEIVED)).isEmpty();
        assertThat(MailEventExtractor.extract("Interview on 12 November 2026 at 14:30", null)).isEmpty();
    }

    @Test
    void aTimeRangeIsNotAZone() {
        MailEventExtractor.Found found = MailEventExtractor.extract("Interview on 12 November 2026 10:00-11:00", RECEIVED).orElseThrow();

        assertThat(found.zoneKnown()).isFalse();
        assertThat(found.start()).isEqualTo(LocalDateTime.parse("2026-11-12T10:00"));
    }

    @Test
    void theSameTimeWrittenTwiceIsOneEvent() {
        String text = "Interview on Thursday 12 November 2026 at 14:30. Calendar: 2026-11-12 14:30.";

        assertThat(MailEventExtractor.extract(text, RECEIVED)).isPresent();
    }

    @Test
    void theDateNearInterviewWordsBeatsAFooterDate() {
        String text = "Our office reopens on 20 November 2026 at 09:00.\n\n" + "x".repeat(300)
                + "\nYour interview is on 12 November 2026 at 14:30.";

        assertThat(MailEventExtractor.extract(text, RECEIVED).orElseThrow().start()).isEqualTo(LocalDateTime.parse("2026-11-12T14:30"));
    }

    @Test
    void theMatchedTextIsKeptForTransparency() {
        MailEventExtractor.Found found = MailEventExtractor.extract("Interview on Thursday, 12 November at\n14:30 please", RECEIVED).orElseThrow();

        assertThat(found.text()).isEqualTo("12 November at 14:30");
    }

    @Test
    void aYearAheadIsTheLimit() {
        assertThat(MailEventExtractor.extract("Interview on 4 November 2027 at 10:00", RECEIVED)).isPresent();
        assertThat(MailEventExtractor.extract("Interview on 7 November 2027 at 10:00", RECEIVED)).isEmpty();
    }

    @Test
    void aHugeTextIsReadQuickly() {
        String filler = "1.2.3 v1.12.2026 $12.11 ".repeat(2_000) + "Interview 12 November 2026 at 14:30";
        String dates = "12 Nov 10:00 ".repeat(4_000);

        assertThat(MailEventExtractor.extract(filler, RECEIVED)).isPresent();
        assertThat(MailEventExtractor.extract(dates, RECEIVED)).isPresent();
    }
}
