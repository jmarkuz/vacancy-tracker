package com.vacancytracker.collection.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CountryDetectorTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Berlin, Germany|DE",
            "Praha, Česko|CZ",
            "Prague, Czech Republic|CZ",
            "Deutschland|DE",
            "Leeds, UK|GB",
            "Remote (Poland)|PL",
            "Austin, TX, USA|US",
            "Wien / Österreich|AT",
            "Remote - Canada|CA"})
    void readsACountryName(String location, String code) {
        assertThat(CountryDetector.detect(location)).contains(code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Remote", "Worldwide", "Europe", "Berlin", "Indianapolis, IN", "Wilmington, DE", "Atlanta, Georgia", "Granada", "Jersey",
            "Germany, France", "Remote, Germany or Poland"})
    void givesNothingWhenThereIsNoSingleCountry(String location) {
        assertThat(CountryDetector.detect(location)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void blankLocationHasNoCountry(String location) {
        assertThat(CountryDetector.detect(location)).isEmpty();
    }
}
