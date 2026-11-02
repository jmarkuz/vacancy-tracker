package com.vacancytracker.collection.http;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** ETag and Last-Modified per URL, so unchanged feeds can be answered with 304 (T-028). */
@Repository
public class ConditionalCache {

    public record Validators(String etag, String lastModified) {
    }

    private final JdbcClient jdbc;

    public ConditionalCache(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Validators> find(String url) {
        return jdbc.sql("SELECT etag, last_modified FROM http_cache WHERE url = :url")
                .param("url", url)
                .query((rs, n) -> new Validators(rs.getString("etag"), rs.getString("last_modified")))
                .optional();
    }

    public void save(String url, Validators validators) {
        jdbc.sql("INSERT INTO http_cache (url, etag, last_modified, fetched_at) VALUES (:url, :etag, :lm, now())"
                        + " ON CONFLICT (url) DO UPDATE SET etag = EXCLUDED.etag, last_modified = EXCLUDED.last_modified,"
                        + " fetched_at = now()")
                .param("url", url)
                .param("etag", validators.etag())
                .param("lm", validators.lastModified())
                .update();
    }
}
