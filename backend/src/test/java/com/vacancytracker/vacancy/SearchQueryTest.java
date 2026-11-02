package com.vacancytracker.vacancy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SearchQueryTest {

    @Test
    void everyWordBecomesAPrefixTermJoinedWithAnd() {
        assertThat(SearchQuery.toTsQuery("Kotlin senior")).contains("kotlin:* & senior:*");
    }

    @Test
    void syntaxCharactersCannotReachTheQuery() {
        assertThat(SearchQuery.toTsQuery("a' | !b & (c) <-> d:*")).contains("a:* & b:* & c:* & d:*");
        assertThat(SearchQuery.toTsQuery("'; DROP TABLE vacancy; --")).contains("drop:* & table:* & vacancy:*");
    }

    @Test
    void unicodeLettersAndDigitsAreKept() {
        assertThat(SearchQuery.toTsQuery("Київ C++ 2026")).contains("київ:* & c:* & 2026:*");
    }

    @Test
    void textWithNoWordsIsEmpty() {
        assertThat(SearchQuery.toTsQuery(null)).isEmpty();
        assertThat(SearchQuery.toTsQuery("   ")).isEmpty();
        assertThat(SearchQuery.toTsQuery("&|!()")).isEmpty();
    }

    @Test
    void lengthAndWordCountAreCapped() {
        String q = SearchQuery.toTsQuery("a b c d e f g h i j " + "x".repeat(100)).orElseThrow();

        assertThat(q.split(" & ")).hasSize(SearchQuery.MAX_TERMS);
        assertThat(SearchQuery.toTsQuery("x".repeat(100)).orElseThrow()).hasSize(SearchQuery.MAX_TERM_LENGTH + 2);
    }

    @Test
    void aDottedWordMatchesAsAWholeAndAsItsParts() {
        assertThat(SearchQuery.toTsQuery("Node.js senior")).contains("('node.js':* | (node:* & js:*)) & senior:*");
    }
}
