package com.vacancytracker.collection.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vacancytracker.collection.http.UpstreamException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class BoardFetchTest {

    private static final Logger LOG = LoggerFactory.getLogger(BoardFetchTest.class);

    private static RawPosting posting(String id) {
        return new RawPosting("ats-test", id, "https://example.test/" + id, "Job " + id, "Acme", null, null, null, null, null, null, null, null);
    }

    private static BoardFetch.BoardReader answers(Map<String, Object> byBoard) {
        return board -> {
            Object answer = byBoard.get(board);
            if (answer instanceof RuntimeException e) {
                throw e;
            }
            @SuppressWarnings("unchecked")
            Optional<List<RawPosting>> result = (Optional<List<RawPosting>>) answer;
            return result;
        };
    }

    @Test
    void aBoardThatFailsDoesNotStopTheOthersAndTheRunCarriesAWarning() {
        FetchOutcome outcome = BoardFetch.fetchAll("Test board", List.of("a", "broken", "c"), answers(Map.of(
                "a", Optional.of(List.of(posting("1"))),
                "broken", new UpstreamException("HTTP 500 after 4 attempts"),
                "c", Optional.of(List.of(posting("3"))))), LOG);

        assertThat(outcome.postings()).extracting(RawPosting::externalId).containsExactly("1", "3");
        assertThat(outcome.postings()).as("each posting keeps the board it was read from").extracting(RawPosting::board)
                .containsExactly("a", "c");
        assertThat(outcome.failedBoards()).containsExactly("broken");
        assertThat(outcome.unchanged()).isFalse();
        assertThat(outcome.warning()).startsWith("1 of 3 Test boards could not be read.")
                .contains("Test board broken: HTTP 500 after 4 attempts");
    }

    @Test
    void anErrorThatAlreadyNamesTheBoardIsNotPrefixedAgain() {
        FetchOutcome outcome = BoardFetch.fetchAll("Test board", List.of("a", "b"), answers(Map.of(
                "a", Optional.of(List.of(posting("1"))),
                "b", new UpstreamException("Test board b responded HTTP 404"))), LOG);

        assertThat(outcome.warning()).contains("Test board b responded HTTP 404").doesNotContain("Test board b: Test board b");
    }

    @Test
    void aMisconfiguredBoardIsIsolatedToo() {
        FetchOutcome outcome = BoardFetch.fetchAll("Test board", List.of("bad host", "a"), answers(Map.of(
                "bad host", new IllegalArgumentException("Only http and https URLs can be fetched"),
                "a", Optional.of(List.of(posting("1"))))), LOG);

        assertThat(outcome.postings()).hasSize(1);
        assertThat(outcome.warning()).contains("bad host");
    }

    @Test
    void whenNoBoardCouldBeReadTheSourceFailsAndNamesTheFirstProblem() {
        assertThatThrownBy(() -> BoardFetch.fetchAll("Test board", List.of("a", "b"), answers(Map.of(
                "a", new UpstreamException("HTTP 503 after 4 attempts"),
                "b", new UpstreamException("returned invalid JSON"))), LOG))
                .isInstanceOf(UpstreamException.class)
                .hasMessageStartingWith("No Test board could be read.")
                .hasMessageContaining("Test board a: HTTP 503 after 4 attempts");
    }

    @Test
    void whenEveryBoardIsReadThereIsNoWarning() {
        FetchOutcome outcome = BoardFetch.fetchAll("Test board", List.of("a", "b"), answers(Map.of(
                "a", Optional.of(List.of(posting("1"))),
                "b", Optional.of(List.of(posting("2"))))), LOG);

        assertThat(outcome.warning()).isNull();
        assertThat(outcome.failedBoards()).isEmpty();
        assertThat(outcome.postings()).hasSize(2);
    }

    @Test
    void everyBoardNotModifiedMeansNoChange() {
        FetchOutcome outcome = BoardFetch.fetchAll("Test board", List.of("a", "b"),
                board -> Optional.empty(), LOG);

        assertThat(outcome.unchanged()).isTrue();
    }

    @Test
    void aFailedBoardNextToAnUnchangedOneIsNotReportedAsUnchanged() {
        FetchOutcome outcome = BoardFetch.fetchAll("Test board", List.of("same", "broken"), answers(Map.of(
                "same", Optional.empty(),
                "broken", new UpstreamException("HTTP 500 after 4 attempts"))), LOG);

        assertThat(outcome.unchanged()).isFalse();
        assertThat(outcome.warning()).isNotNull();
    }

    @Test
    void noBoardsMeansNothingToDoAndNoWarning() {
        FetchOutcome outcome = BoardFetch.fetchAll("Test board", List.of(), board -> Optional.empty(), LOG);

        assertThat(outcome.postings()).isEmpty();
        assertThat(outcome.unchanged()).isFalse();
        assertThat(outcome.warning()).isNull();
    }
}
