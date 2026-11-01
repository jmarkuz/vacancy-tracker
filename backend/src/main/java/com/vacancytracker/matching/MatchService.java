package com.vacancytracker.matching;

import com.vacancytracker.common.NotFoundException;
import com.vacancytracker.matching.RuleEngine.RuleResult;
import com.vacancytracker.matching.Scorer.ScoreOutcome;
import com.vacancytracker.matching.rates.RateSalaryConverter;
import com.vacancytracker.vacancy.Vacancy;
import com.vacancytracker.vacancy.VacancyRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scores one vacancy against every enabled profile and stores the result (T-052). A profile applies only
 * when it uses the source that saw the vacancy, or uses all sources. The vacancy's match score is the best
 * score across profiles that did not filter it out. Statuses are never changed here: applying the inbox
 * threshold is the recalculation job's task (T-053).
 */
@Service
public class MatchService {

    private final SearchProfileService profiles;
    private final VacancyMatchRepository matches;
    private final VacancyRepository vacancies;
    private final RuleEngine engine;
    private final Clock clock;

    public MatchService(SearchProfileService profiles, VacancyMatchRepository matches, VacancyRepository vacancies,
                        RateSalaryConverter converter, Clock clock) {
        this.profiles = profiles;
        this.matches = matches;
        this.vacancies = vacancies;
        this.engine = new RuleEngine(converter);
        this.clock = clock;
    }

    /** Evaluates and stores the vacancy's matches, replacing earlier ones. Returns the stored matches. */
    @Transactional
    public List<VacancyMatch> match(UUID vacancyId) {
        Vacancy vacancy = Vacancy.restore(vacancies.findById(vacancyId)
                .orElseThrow(() -> new NotFoundException("Vacancy not found: " + vacancyId)));
        List<String> adapters = vacancies.findSourceAdapters(vacancyId);
        Map<String, Object> facts = VacancyFacts.of(vacancy, adapters);

        List<UUID> applicable = new ArrayList<>();
        for (SearchProfile profile : profiles.list()) {
            if (!profile.enabled() || !appliesTo(profile, adapters)) {
                continue;
            }
            RuleResult result = engine.evaluate(profile.criteria(), facts);
            ScoreOutcome outcome = Scorer.score(profile.criteria(), result, 0);
            matches.upsert(new VacancyMatch(vacancyId, profile.id(), profile.version(), outcome.score(),
                    outcome.hidden(), outcome.reasons(), clock.instant()));
            applicable.add(profile.id());
        }
        matches.deleteExcept(vacancyId, applicable);
        vacancies.updateMatchScore(vacancyId, matches.bestScore(vacancyId).orElse(null));
        return matches.findForVacancy(vacancyId);
    }

    @Transactional(readOnly = true)
    public List<VacancyMatch> matchesOf(UUID vacancyId) {
        if (vacancies.findById(vacancyId).isEmpty()) {
            throw new NotFoundException("Vacancy not found: " + vacancyId);
        }
        return matches.findForVacancy(vacancyId);
    }

    static boolean appliesTo(SearchProfile profile, List<String> adapters) {
        return profile.sources().contains("all") || adapters.stream().anyMatch(profile.sources()::contains);
    }
}
