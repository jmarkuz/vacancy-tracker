package com.vacancytracker.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CsvParserTest {

    @Test
    void parsesQuotesEmbeddedCommasNewlinesAndBom() {
        var rows = CsvParser.parse("﻿a,b\r\n\"x, \"\"y\"\"\",\"line1\nline2\"\r\nlast,\r\n");

        assertThat(rows).containsExactly(
                java.util.List.of("a", "b"),
                java.util.List.of("x, \"y\"", "line1\nline2"),
                java.util.List.of("last", ""));
    }

    @Test
    void unterminatedQuoteIsRejected() {
        assertThatThrownBy(() -> CsvParser.parse("a,\"b")).isInstanceOf(IllegalArgumentException.class);
    }
}
