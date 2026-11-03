package com.vacancytracker.application.mail;

import com.vacancytracker.vacancy.VacancyStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Explicit SQL for correspondence, plus the vacancies a mail may be about. */
@Repository
class CorrespondenceRepository {

    private static final String COLUMNS = "id, mail_key, folder, sender, sender_name, subject, received_at, snippet, category, vacancy_id,"
            + " link_reason, suggested_status, suggestion_state, created_at";

    private final JdbcClient jdbc;

    CorrespondenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Stores the mail unless one with the same key is stored already; answers whether it was new. */
    boolean insert(Correspondence c) {
        return jdbc.sql("INSERT INTO correspondence (" + COLUMNS + ") VALUES (:id, :key, :folder, :sender, :name, :subject, :received,"
                        + " :snippet, :category, :vacancy, :reason, :suggested, :state, :created) ON CONFLICT (mail_key) DO NOTHING")
                .param("id", c.id())
                .param("key", c.mailKey())
                .param("folder", c.folder())
                .param("sender", c.sender())
                .param("name", c.senderName())
                .param("subject", c.subject())
                .param("received", utc(c.receivedAt()))
                .param("snippet", c.snippet())
                .param("category", c.category())
                .param("vacancy", c.vacancyId())
                .param("reason", c.linkReason())
                .param("suggested", c.suggestedStatus())
                .param("state", c.suggestionState())
                .param("created", utc(c.createdAt()))
                .update() == 1;
    }

    void updateLink(UUID id, UUID vacancyId, String reason, String suggested, String state) {
        jdbc.sql("UPDATE correspondence SET vacancy_id = :v, link_reason = :r, suggested_status = :s, suggestion_state = :st WHERE id = :id")
                .param("v", vacancyId).param("r", reason).param("s", suggested).param("st", state).param("id", id).update();
    }

    /**
     * The vacancy the owner last linked a mail from this sender to by hand, if it is still alive. A vacancy the owner
     * dismissed, or that expired or was hidden, takes no more mail: a new message from the same person is about
     * something else. A rejected or withdrawn application still gets its follow-ups.
     */
    Optional<UUID> manualLinkFor(String sender) {
        return jdbc.sql("SELECT c.vacancy_id FROM correspondence c JOIN vacancy v ON v.id = c.vacancy_id"
                        + " WHERE lower(c.sender) = lower(:s) AND c.link_reason = :r"
                        + " AND v.status NOT IN ('Dismissed', 'Hidden', 'Expired')"
                        + " ORDER BY c.received_at DESC NULLS LAST, c.created_at DESC LIMIT 1")
                .param("s", sender).param("r", CorrespondenceService.LINKED_BY_YOU).query(UUID.class).optional();
    }

    /** Stored mails from this sender that belong to no application yet. */
    List<Correspondence> unlinkedFrom(String sender) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM correspondence WHERE lower(sender) = lower(:s) AND vacancy_id IS NULL")
                .param("s", sender).query(CorrespondenceRepository::map).list();
    }

    /** The applications a mail can be linked to by hand: anything past the Inbox that is still alive, newest first. */
    List<CorrespondenceService.ApplicationOption> applications() {
        return jdbc.sql("SELECT id, title, company, status FROM vacancy WHERE status NOT IN ('New', 'Dismissed', 'Hidden', 'Expired')"
                        + " ORDER BY updated_at DESC LIMIT 500")
                .query((rs, n) -> new CorrespondenceService.ApplicationOption(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("company"),
                        rs.getString("status")))
                .list();
    }

    void updateState(UUID id, String state) {
        jdbc.sql("UPDATE correspondence SET suggestion_state = :st WHERE id = :id").param("st", state).param("id", id).update();
    }

    Optional<Correspondence> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM correspondence WHERE id = :id").param("id", id)
                .query(CorrespondenceRepository::map).optional();
    }

    Optional<Correspondence> findByKey(String key) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM correspondence WHERE mail_key = :k").param("k", key)
                .query(CorrespondenceRepository::map).optional();
    }

    List<Correspondence> list(UUID vacancyId, boolean pendingOnly, int limit) {
        String where = (vacancyId == null ? "TRUE" : "vacancy_id = :v") + (pendingOnly ? " AND suggestion_state = 'pending'" : "");
        var query = jdbc.sql("SELECT " + COLUMNS + " FROM correspondence WHERE " + where
                + " ORDER BY received_at DESC NULLS LAST, created_at DESC LIMIT :limit").param("limit", limit);
        if (vacancyId != null) {
            query = query.param("v", vacancyId);
        }
        return query.query(CorrespondenceRepository::map).list();
    }

    /** Removes the stored mails with these ids; answers how many existed. */
    int deleteAll(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbc.sql("DELETE FROM correspondence WHERE id IN (:ids)").param("ids", ids).update();
    }

    /** Removes the stored mails received (or, with no date, stored) before the cutoff; answers how many. */
    int deleteBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM correspondence WHERE COALESCE(received_at, created_at) < :cutoff")
                .param("cutoff", utc(cutoff)).update();
    }

    long pendingCount() {
        return jdbc.sql("SELECT count(*) FROM correspondence WHERE suggestion_state = 'pending'").query(Long.class).single();
    }

    /** Vacancies an email can be about: anything the owner has acted on, not the untouched Inbox or what they dropped. */
    List<MailLinker.Candidate> candidates() {
        return jdbc.sql("""
                        SELECT v.id, v.title, v.company, v.url, v.status,
                               EXISTS (SELECT 1 FROM application_package p WHERE p.vacancy_id = v.id AND p.status = 'submitted') AS submitted
                        FROM vacancy v
                        WHERE v.status NOT IN ('New', 'Dismissed', 'Hidden', 'Expired')
                        ORDER BY v.updated_at DESC LIMIT 2000
                        """)
                .query((rs, n) -> {
                    VacancyStatus status = VacancyStatus.fromLabel(rs.getString("status"));
                    boolean applied = rs.getBoolean("submitted") || status != VacancyStatus.SAVED;
                    return new MailLinker.Candidate(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("company"),
                            rs.getString("url"), applied);
                })
                .list();
    }

    private static OffsetDateTime utc(Instant at) {
        return at == null ? null : OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }

    private static Correspondence map(ResultSet rs, int row) throws SQLException {
        OffsetDateTime received = rs.getObject("received_at", OffsetDateTime.class);
        return new Correspondence(rs.getObject("id", UUID.class), rs.getString("mail_key"), rs.getString("folder"), rs.getString("sender"),
                rs.getString("sender_name"), rs.getString("subject"), received == null ? null : received.toInstant(), rs.getString("snippet"),
                rs.getString("category"), rs.getObject("vacancy_id", UUID.class), rs.getString("link_reason"),
                rs.getString("suggested_status"), rs.getString("suggestion_state"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
