package com.vacancytracker.collection.rates;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

/** Reads the ECB daily reference rates XML: one dated Cube holding a Cube per currency (EUR base). */
public final class EcbFeedParser {

    public record Parsed(LocalDate date, Map<String, BigDecimal> perEur) {
    }

    private EcbFeedParser() {
    }

    /** Throws IllegalArgumentException when the document is not a usable rates feed. */
    public static Parsed parse(String xml) {
        Document doc = Jsoup.parse(xml, "", Parser.xmlParser());
        Element dated = doc.selectFirst("Cube[time]");
        if (dated == null) {
            throw new IllegalArgumentException("The ECB feed has no dated rates");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(dated.attr("time"));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("The ECB feed has an invalid date");
        }
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        for (Element cube : dated.select("Cube[currency][rate]")) {
            try {
                BigDecimal rate = new BigDecimal(cube.attr("rate"));
                if (rate.signum() > 0) {
                    rates.put(cube.attr("currency").toUpperCase(java.util.Locale.ROOT), rate);
                }
            } catch (NumberFormatException e) {
                // A single unreadable rate is skipped; the others stay usable.
                continue;
            }
        }
        if (rates.isEmpty()) {
            throw new IllegalArgumentException("The ECB feed has no usable rates");
        }
        return new Parsed(date, rates);
    }
}
