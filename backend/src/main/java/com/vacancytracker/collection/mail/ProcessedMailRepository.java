package com.vacancytracker.collection.mail;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProcessedMailRepository {

    private final JdbcClient jdbc;

    public ProcessedMailRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Which of the given keys were handled before. */
    public Set<String> known(Collection<String> keys) {
        if (keys.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("SELECT mail_key FROM processed_mail WHERE mail_key = ANY (CAST(:keys AS TEXT[]))")
                .param("keys", keys.toArray(String[]::new))
                .query(String.class)
                .list());
    }

    public void markProcessed(String key) {
        jdbc.sql("INSERT INTO processed_mail (mail_key) VALUES (:key) ON CONFLICT DO NOTHING").param("key", key).update();
    }

    /** Forgets every handled key that starts with the prefix, so those messages are offered again; answers how many. */
    public int forgetWithPrefix(String prefix) {
        return jdbc.sql("DELETE FROM processed_mail WHERE starts_with(mail_key, :prefix)").param("prefix", prefix).update();
    }
}
