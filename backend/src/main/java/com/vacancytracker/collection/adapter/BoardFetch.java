package com.vacancytracker.collection.adapter;

import com.vacancytracker.collection.http.UpstreamException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;

/**
 * Reads several company boards (Greenhouse boards, Lever sites, Ashby boards) one by one so that a board which cannot be
 * read does not stop the others. A board that fails is logged and counted; the postings of the boards that were read are
 * kept.
 *
 * <p>The outcome follows the rules of ADR-014 and ADR-037: when every board failed the source fails (the circuit breaker
 * counts it); when only some failed the run keeps what it read but carries a warning, so it does not count as a clean read
 * of the source and the vacancies of the unreadable board are not mistaken for gone.
 */
public final class BoardFetch {

    /** Reads one board. Empty means the source answered "not modified" for it; a failure is an UpstreamException. */
    @FunctionalInterface
    public interface BoardReader {
        Optional<List<RawPosting>> read(String board);
    }

    private BoardFetch() {
    }

    /**
     * @param label how one board is called in messages, for example "Greenhouse board"; its plural adds an "s"
     */
    public static FetchOutcome fetchAll(String label, List<String> boards, BoardReader reader, Logger log) {
        List<RawPosting> postings = new ArrayList<>();
        List<String> failedBoards = new ArrayList<>();
        int read = 0;
        int unchanged = 0;
        int failed = 0;
        String firstError = null;
        for (String board : boards) {
            try {
                Optional<List<RawPosting>> result = reader.read(board);
                read++;
                if (result.isPresent()) {
                    // The board is kept with each posting, so that expiry can tell which board a vacancy was seen on.
                    result.get().forEach(p -> postings.add(p.withBoard(board)));
                } else {
                    unchanged++;
                }
            } catch (UpstreamException | IllegalArgumentException e) {
                failed++;
                failedBoards.add(board);
                String reason = reasonOf(label, board, e);
                firstError = firstError == null ? reason : firstError;
                log.warn("{}", reason);
            }
        }
        if (read == 0 && failed > 0) {
            throw new UpstreamException("No " + label + " could be read. " + firstError);
        }
        if (failed > 0) {
            return FetchOutcome.of(postings)
                    .withFailedBoards(failedBoards)
                    .withWarning(failed + " of " + (read + failed) + " " + label + "s could not be read. " + firstError);
        }
        // Every board answered "not modified": nothing changed since the last run, so what was seen stays seen.
        return !boards.isEmpty() && unchanged == boards.size() ? FetchOutcome.noChange() : FetchOutcome.of(postings);
    }

    /** The board is always named, also when the error comes from the HTTP client and does not say which board it was. */
    private static String reasonOf(String label, String board, RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return mentions(message, board) ? message : label + " " + board + ": " + message;
    }

    /** True when the board appears as a whole name, so a short name such as "a" is not found inside "after". */
    private static boolean mentions(String message, String board) {
        return Pattern.compile("(?<![\\p{L}\\p{N}_-])" + Pattern.quote(board) + "(?![\\p{L}\\p{N}_-])").matcher(message).find();
    }
}
