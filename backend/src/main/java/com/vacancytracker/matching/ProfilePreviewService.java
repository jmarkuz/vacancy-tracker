package com.vacancytracker.matching;

import com.vacancytracker.matching.RuleEngine.RuleResult;
import com.vacancytracker.matching.Scorer.ScoreOutcome;
import com.vacancytracker.matching.rates.RateSalaryConverter;
import com.vacancytracker.vacancy.Vacancy;
import com.vacancytracker.vacancy.VacancyRepository;
import com.vacancytracker.vacancy.VacancyState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Live preview of a profile draft (T-056, docs/search-profiles.md section 9): "of N vacancies, M would match,
 * K of them would land in the Inbox", with sample vacancies and the reasons for their scores. It runs the same
 * rule engine and scorer as the real matching but writes nothing: no match rows, no scores, no statuses.
 */
@Service
public class ProfilePreviewService {

    static final int BATCH = 500;
    static final int INBOX_SAMPLES = 3;
    static final int BELOW_SAMPLES = 1;
    static final int FILTERED_SAMPLES = 2;

    /** kind is inbox, below-threshold or filtered-out. score is null for a filtered vacancy. */
    public record Sample(UUID vacancyId, String title, String company, String kind, Integer score, List<MatchReason> reasons) {
    }

    /**
     * total: all vacancies. applicable: those seen by the profile's sources. matched: applicable and not filtered
     * out. inbox: matched with a score at or above the profile's inboxMinScore. filteredOut: applicable but filtered.
     */
    public record Preview(long total, long applicable, long matched, long inbox, long filteredOut, List<Sample> samples) {
    }

    private final ProfileValidator validator;
    private final VacancyRepository vacancies;
    private final java.time.Clock clock;
    private final RuleEngine engine;
    private final CompanyListService lists;
    private final int allowlistBonus;

    public ProfilePreviewService(ProfileValidator validator, VacancyRepository vacancies, RateSalaryConverter converter,
                                CompanyListService lists, java.time.Clock clock,
                                @org.springframework.beans.factory.annotation.Value("${app.matching.allowlist-bonus:10}") int allowlistBonus) {
        this.clock = clock;
        this.validator = validator;
        this.vacancies = vacancies;
        this.engine = new RuleEngine(converter);
        this.lists = lists;
        this.allowlistBonus = allowlistBonus;
    }

    /** Throws ProfileValidationException when the draft is not valid. Never writes. */
    @Transactional(readOnly = true)
    public Preview preview(ProfileInput draft) {
        return preview(draft, null);
    }

    /** profileId is the stored profile the draft would replace, so its own allow and block entries apply as they will after saving. */
    @Transactional(readOnly = true)
    public Preview preview(ProfileInput draft, UUID profileId) {
        ProfileInput profile = validator.validate(draft);
        SearchProfile asProfile = new SearchProfile(null, profile.name(), profile.enabled(), profile.sources(),
                profile.scheduleOverride(), profile.thresholds(), profile.criteria(), 0, null);
        int inboxMin = profile.thresholds().inboxMinScore();
        CompanyLists companyLists = lists.snapshot();

        List<Sample> inbox = new ArrayList<>();
        List<Sample> below = new ArrayList<>();
        List<Sample> filtered = new ArrayList<>();
        long applicable = 0;
        long matched = 0;
        long inboxCount = 0;
        long filteredOut = 0;

        UUID after = null;
        while (true) {
            List<VacancyState> batch = vacancies.findBatchAfter(after, BATCH);
            if (batch.isEmpty()) {
                break;
            }
            after = batch.get(batch.size() - 1).id();
            Map<UUID, List<String>> adapters = vacancies.findSourceAdaptersFor(batch.stream().map(VacancyState::id).toList());
            for (VacancyState state : batch) {
                List<String> sources = adapters.getOrDefault(state.id(), List.of());
                if (!MatchService.appliesTo(asProfile, sources)) {
                    continue;
                }
                applicable++;
                Vacancy vacancy = Vacancy.restore(state);
                Map<String, Object> facts = VacancyFacts.of(vacancy, sources, clock.instant());
                RuleResult result = engine.evaluate(profile.criteria(), facts);
                ScoreOutcome outcome = Scorer.tooOld(facts.get("postedAge"), profile.thresholds().maxAgeDays())
                        .orElseGet(() -> Scorer.score(profile.criteria(), result,
                                companyLists.verdict(profileId, vacancy.getCompany()), allowlistBonus));
                if (outcome.hidden()) {
                    filteredOut++;
                    keep(filtered, sample(vacancy, "filtered-out", outcome), FILTERED_SAMPLES);
                    continue;
                }
                matched++;
                if (outcome.score() >= inboxMin || outcome.reasons().stream().anyMatch(r -> "allowlist".equals(r.kind()))) {
                    inboxCount++;
                    keep(inbox, sample(vacancy, "inbox", outcome), INBOX_SAMPLES);
                } else {
                    keep(below, sample(vacancy, "below-threshold", outcome), BELOW_SAMPLES);
                }
            }
        }

        List<Sample> samples = new ArrayList<>(inbox);
        samples.addAll(below);
        samples.addAll(filtered);
        return new Preview(vacancies.countAll(), applicable, matched, inboxCount, filteredOut, List.copyOf(samples));
    }

    private static Sample sample(Vacancy v, String kind, ScoreOutcome outcome) {
        return new Sample(v.getId(), v.getTitle(), v.getCompany(), kind, outcome.score(), outcome.reasons());
    }

    /** Keeps the best few samples: highest score first, then by title and id so the choice is stable. */
    private static void keep(List<Sample> list, Sample candidate, int limit) {
        list.add(candidate);
        list.sort(Comparator.comparing((Sample s) -> s.score() == null ? -1 : s.score()).reversed()
                .thenComparing(Sample::title).thenComparing(Sample::vacancyId));
        while (list.size() > limit) {
            list.remove(list.size() - 1);
        }
    }
}
