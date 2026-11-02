package com.vacancytracker.matching;

import com.vacancytracker.common.NotFoundException;
import com.vacancytracker.matching.RuleEngine.RuleResult;
import com.vacancytracker.matching.Scorer.ScoreOutcome;
import com.vacancytracker.matching.rates.RateSalaryConverter;
import com.vacancytracker.vacancy.StatusEvent;
import com.vacancytracker.vacancy.Vacancy;
import com.vacancytracker.vacancy.VacancyRepository;
import com.vacancytracker.vacancy.VacancyStatus;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scores vacancies against the enabled profiles and stores the result (T-052, T-053). A profile applies only
 * when it uses the source that saw the vacancy, or all sources. The vacancy's match score is the best score
 * across profiles that did not filter it out.
 *
 * Inbox rule (ADR-026): a New vacancy that no applicable profile accepts (filtered out, or below that profile's
 * inboxMinScore) becomes Hidden, and an automatically hidden one is restored when it qualifies again. Automation
 * only acts while the vacancy's whole status history is automatic, so anything the user decided stays as it is.
 */
@Service
public class MatchService {

    static final String HIDDEN_NOTE = "auto-hidden: filtered out or below the inbox threshold";
    static final String RESTORED_NOTE = "auto-restored: matches the profiles again";
    private static final String AUTO_PREFIX = "auto-";

    /** Matches vacancies against a fixed set of profiles, loaded once. Used by runs and recalculation. */
    public interface MatchSession {

        List<VacancyMatch> match(UUID vacancyId);
    }

    private final SearchProfileService profiles;
    private final VacancyMatchRepository matches;
    private final VacancyRepository vacancies;
    private final RuleEngine engine;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final CompanyListService lists;
    private final int allowlistBonus;

    public MatchService(SearchProfileService profiles, VacancyMatchRepository matches, VacancyRepository vacancies,
                        RateSalaryConverter converter, Clock clock, PlatformTransactionManager transactions,
                        CompanyListService lists,
                        @org.springframework.beans.factory.annotation.Value("${app.matching.allowlist-bonus:10}") int allowlistBonus) {
        this.profiles = profiles;
        this.matches = matches;
        this.vacancies = vacancies;
        this.engine = new RuleEngine(converter);
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
        this.lists = lists;
        this.allowlistBonus = allowlistBonus;
    }

    /** A session over the profiles as they are now. Each vacancy is matched in its own transaction. */
    public MatchSession session() {
        List<SearchProfile> enabled = profiles.list().stream().filter(SearchProfile::enabled).toList();
        CompanyLists companyLists = lists.snapshot();
        return vacancyId -> tx.execute(status -> matchWith(enabled, companyLists, vacancyId));
    }

    /** Evaluates and stores one vacancy's matches, replacing earlier ones. */
    public List<VacancyMatch> match(UUID vacancyId) {
        return session().match(vacancyId);
    }

    @Transactional(readOnly = true)
    public List<VacancyMatch> matchesOf(UUID vacancyId) {
        if (vacancies.findById(vacancyId).isEmpty()) {
            throw new NotFoundException("Vacancy not found: " + vacancyId);
        }
        return matches.findForVacancy(vacancyId);
    }

    private List<VacancyMatch> matchWith(List<SearchProfile> enabled, CompanyLists companyLists, UUID vacancyId) {
        Vacancy vacancy = Vacancy.restore(vacancies.findById(vacancyId)
                .orElseThrow(() -> new NotFoundException("Vacancy not found: " + vacancyId)));
        List<String> adapters = vacancies.findSourceAdapters(vacancyId);
        Map<String, Object> facts = VacancyFacts.of(vacancy, adapters);

        List<SearchProfile> applicable = new ArrayList<>();
        for (SearchProfile profile : enabled) {
            if (!appliesTo(profile, adapters)) {
                continue;
            }
            RuleResult result = engine.evaluate(profile.criteria(), facts);
            ScoreOutcome outcome = Scorer.score(profile.criteria(), result,
                    companyLists.verdict(profile.id(), vacancy.getCompany()), allowlistBonus);
            matches.upsert(new VacancyMatch(vacancyId, profile.id(), profile.version(), outcome.score(),
                    outcome.hidden(), outcome.reasons(), clock.instant()));
            applicable.add(profile);
        }
        matches.deleteExcept(vacancyId, applicable.stream().map(SearchProfile::id).toList());
        vacancies.updateMatchScore(vacancyId, matches.bestScore(vacancyId).orElse(null));
        List<VacancyMatch> stored = matches.findForVacancy(vacancyId);
        applyInboxRule(vacancy, applicable, stored);
        return stored;
    }

    private void applyInboxRule(Vacancy vacancy, List<SearchProfile> applicable, List<VacancyMatch> stored) {
        VacancyStatus status = vacancy.getStatus();
        if (status != VacancyStatus.NEW && status != VacancyStatus.HIDDEN) {
            return;
        }
        // A vacancy the user added or imported by hand has no source: automation never hides what the user put there.
        if (vacancies.findSourceAdaptersFor(List.of(vacancy.getId())).getOrDefault(vacancy.getId(), List.of()).isEmpty()) {
            return;
        }
        List<StatusEvent> history = vacancies.findHistory(vacancy.getId());
        if (!history.stream().allMatch(e -> e.note() != null && e.note().startsWith(AUTO_PREFIX))) {
            return;
        }
        boolean accepted = accepted(applicable, stored);
        StatusEvent event = null;
        if (status == VacancyStatus.NEW && !accepted) {
            event = vacancy.changeStatusAutomatically(VacancyStatus.HIDDEN, clock.instant(), HIDDEN_NOTE);
        } else if (status == VacancyStatus.HIDDEN && accepted && !history.isEmpty()) {
            event = vacancy.changeStatusAutomatically(VacancyStatus.NEW, clock.instant(), RESTORED_NOTE);
        }
        if (event != null) {
            vacancies.save(vacancy.toState());
            vacancies.appendStatusEvent(event);
        }
    }

    /** With no applicable profile there is nothing to filter by. Otherwise one acceptance is enough. */
    private static boolean accepted(List<SearchProfile> applicable, List<VacancyMatch> stored) {
        if (applicable.isEmpty()) {
            return true;
        }
        Map<UUID, Integer> minScores = applicable.stream()
                .collect(Collectors.toMap(SearchProfile::id, p -> p.thresholds().inboxMinScore(), (a, b) -> a));
        return stored.stream().anyMatch(m -> !m.hidden() && m.score() != null
                && (m.score() >= minScores.getOrDefault(m.profileId(), Integer.MAX_VALUE)
                || m.reasons().stream().anyMatch(r -> "allowlist".equals(r.kind()))));
    }

    static boolean appliesTo(SearchProfile profile, List<String> adapters) {
        return profile.sources().contains("all") || adapters.stream().anyMatch(profile.sources()::contains);
    }
}
