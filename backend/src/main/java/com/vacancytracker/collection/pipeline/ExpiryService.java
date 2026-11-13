package com.vacancytracker.collection.pipeline;

import com.vacancytracker.vacancy.Vacancy;
import com.vacancytracker.vacancy.VacancyRepository;
import com.vacancytracker.vacancy.VacancyStatus;
import com.vacancytracker.collection.adapter.SourceAdapter;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Expiry (T-045, ADR-014). A New or Saved vacancy becomes Expired after it was missed by N consecutive
 * successful runs of its sources. A source that failed in a run does not count for that run. Email sources never
 * count: an alert email arrives once, so not seeing its vacancy again says nothing about the posting (ADR-030). Nor do
 * the sources that list only a window of their postings (the newest N, a few pages, a configured selection): a posting
 * that has slid out of the window is not gone (ADR-036). A vacancy of a company that the owner stopped following (a board
 * removed from an ATS list, or the list emptied) is left alone as well: the source no longer looks for it (F8).
 */
@Service
public class ExpiryService {

    static final String NOTE = "auto-expired";

    /**
     * Sources that show only part of what is open, so missing from them proves nothing. telegram-forward returns only
     * the posts forwarded since the last run, like an email (ADR-045).
     */
    static final List<String> WINDOWED_SOURCES = List.of("remotive", "jobicy", "arbeitnow", "himalayas", "adzuna", "rss", "html",
            "remoteok", "workingnomads", "telegram-forward");

    private static final String CANDIDATES = """
            SELECT v.id FROM vacancy v
            WHERE v.status IN ('New', 'Saved')
              AND (SELECT count(DISTINCT r.id)
                   FROM run r JOIN run_source_stat s ON s.run_id = r.id
                   WHERE r.started_at > v.last_seen_at
                     AND r.status IN ('completed', 'completed_with_errors')
                     AND s.error_message IS NULL
                     AND s.adapter IN (SELECT vs.adapter FROM vacancy_source vs WHERE vs.vacancy_id = v.id
                                        AND vs.adapter NOT LIKE 'email-%' AND vs.adapter NOT IN (:windowed))
                  ) >= :runs
            """;

    private final JdbcClient jdbc;
    private final VacancyRepository vacancies;
    private final Clock clock;
    private final int runsWithoutSighting;
    private final TransactionTemplate tx;
    /** Adapter id to the companies it still follows; only for sources that work from the owner's list. */
    private final Map<String, Set<String>> followed = new HashMap<>();

    @Autowired
    public ExpiryService(JdbcClient jdbc, VacancyRepository vacancies, Clock clock,
                         PlatformTransactionManager transactions, List<SourceAdapter> adapters,
                         @Value("${app.collection.expiry-runs:3}") int runsWithoutSighting) {
        this.jdbc = jdbc;
        this.vacancies = vacancies;
        this.clock = clock;
        this.runsWithoutSighting = runsWithoutSighting;
        this.tx = new TransactionTemplate(transactions);
        for (SourceAdapter adapter : adapters) {
            adapter.followedCompanies().ifPresent(companies -> followed.put(adapter.id(), companies));
        }
    }

    ExpiryService(JdbcClient jdbc, VacancyRepository vacancies, Clock clock,
                  PlatformTransactionManager transactions, int runsWithoutSighting) {
        this(jdbc, vacancies, clock, transactions, List.of(), runsWithoutSighting);
    }

    /** Returns how many vacancies were expired. Each one is its own transaction. */
    public int expireUnseen() {
        List<UUID> candidates = jdbc.sql(CANDIDATES).param("runs", runsWithoutSighting).param("windowed", WINDOWED_SOURCES).query(UUID.class).list();
        int expired = 0;
        for (UUID id : candidates) {
            if (stillFollowed(id)) {
                tx.executeWithoutResult(status -> expire(id));
                expired++;
            }
        }
        return expired;
    }

    /**
     * False when one of the sources that would prove the posting gone no longer follows its company. Being careful is
     * the safe side: a vacancy kept too long can be dismissed, an expired one is easily missed.
     */
    private boolean stillFollowed(UUID id) {
        if (followed.isEmpty()) {
            return true;
        }
        record Row(String company, String adapter) {
        }
        List<Row> rows = jdbc.sql("""
                SELECT v.company, vs.adapter FROM vacancy v JOIN vacancy_source vs ON vs.vacancy_id = v.id
                WHERE v.id = :id AND vs.adapter NOT LIKE 'email-%' AND vs.adapter NOT IN (:windowed)
                """).param("id", id).param("windowed", WINDOWED_SOURCES)
                .query((rs, i) -> new Row(rs.getString("company"), rs.getString("adapter"))).list();
        for (Row row : rows) {
            Set<String> companies = followed.get(row.adapter());
            if (companies != null && !companies.contains(row.company().toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    private void expire(UUID id) {
        Vacancy vacancy = Vacancy.restore(vacancies.findByIdForUpdate(id).orElseThrow());
        if (vacancy.getStatus() != VacancyStatus.NEW && vacancy.getStatus() != VacancyStatus.SAVED) {
            return;
        }
        var event = vacancy.changeStatus(VacancyStatus.EXPIRED, clock.instant(), NOTE);
        vacancies.save(vacancy.toState());
        vacancies.appendStatusEvent(event);
    }
}
