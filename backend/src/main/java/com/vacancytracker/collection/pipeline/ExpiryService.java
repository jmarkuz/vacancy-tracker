package com.vacancytracker.collection.pipeline;

import com.vacancytracker.vacancy.Vacancy;
import com.vacancytracker.vacancy.VacancyRepository;
import com.vacancytracker.vacancy.VacancyStatus;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
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
 * that has slid out of the window is not gone (ADR-036).
 */
@Service
public class ExpiryService {

    static final String NOTE = "auto-expired";

    /** Sources that show only part of what is open, so missing from them proves nothing. */
    static final List<String> WINDOWED_SOURCES = List.of("remotive", "jobicy", "arbeitnow", "himalayas", "adzuna", "rss", "html");

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

    public ExpiryService(JdbcClient jdbc, VacancyRepository vacancies, Clock clock,
                         PlatformTransactionManager transactions,
                         @Value("${app.collection.expiry-runs:3}") int runsWithoutSighting) {
        this.jdbc = jdbc;
        this.vacancies = vacancies;
        this.clock = clock;
        this.runsWithoutSighting = runsWithoutSighting;
        this.tx = new TransactionTemplate(transactions);
    }

    /** Returns how many vacancies were expired. Each one is its own transaction. */
    public int expireUnseen() {
        List<UUID> candidates = jdbc.sql(CANDIDATES).param("runs", runsWithoutSighting).param("windowed", WINDOWED_SOURCES).query(UUID.class).list();
        for (UUID id : candidates) {
            tx.executeWithoutResult(status -> expire(id));
        }
        return candidates.size();
    }

    private void expire(UUID id) {
        Vacancy vacancy = Vacancy.restore(vacancies.findById(id).orElseThrow());
        if (vacancy.getStatus() != VacancyStatus.NEW && vacancy.getStatus() != VacancyStatus.SAVED) {
            return;
        }
        var event = vacancy.changeStatus(VacancyStatus.EXPIRED, clock.instant(), NOTE);
        vacancies.save(vacancy.toState());
        vacancies.appendStatusEvent(event);
    }
}
