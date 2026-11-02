package com.vacancytracker.vacancy;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Full-text search over title, company and description (T-062) with the GIN index on vacancy.search_vector.
 * Rank is the text rank plus a boost when the words are in the title or the company, so a title match comes first.
 */
@Service
public class VacancySearchService {

    public static final int MAX_LIMIT = 50;

    /** snippet marks matched words with [[ and ]]; the client decides how to show them. */
    public record Hit(UUID id, String title, String company, String status, double rank, String snippet) {
    }

    private final JdbcClient jdbc;

    public VacancySearchService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<Hit> search(String text, int limit) {
        var query = SearchQuery.toTsQuery(text);
        if (query.isEmpty()) {
            return List.of();
        }
        int capped = Math.max(1, Math.min(limit, MAX_LIMIT));
        return jdbc.sql("""
                SELECT id, title, company, status, rank,
                       ts_headline('simple', coalesce(description, ''), to_tsquery('simple', :q),
                                   'StartSel=[[, StopSel=]], MaxFragments=1, MaxWords=18, MinWords=8') AS snippet
                FROM (
                    SELECT id, title, company, status, description,
                           ts_rank(search_vector, to_tsquery('simple', :q))
                             + CASE WHEN to_tsvector('simple', title) @@ to_tsquery('simple', :q) THEN 1.0 ELSE 0 END
                             + CASE WHEN to_tsvector('simple', company) @@ to_tsquery('simple', :q) THEN 0.5 ELSE 0 END AS rank
                    FROM vacancy
                    WHERE search_vector @@ to_tsquery('simple', :q)
                    ORDER BY rank DESC, last_seen_at DESC, id
                    LIMIT :limit
                ) ranked
                ORDER BY rank DESC, id
                """)
                .param("q", query.get())
                .param("limit", capped)
                .query((rs, i) -> new Hit(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("company"),
                        rs.getString("status"), rs.getDouble("rank"), rs.getString("snippet")))
                .list();
    }
}
