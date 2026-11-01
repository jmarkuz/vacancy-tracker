package com.vacancytracker.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.vacancytracker.matching.RuleEngine.CriterionResult;
import com.vacancytracker.matching.RuleEngine.Effect;
import com.vacancytracker.matching.RuleEngine.RuleResult;
import com.vacancytracker.matching.RuleEngine.Verdict;
import com.vacancytracker.matching.Scorer.ScoreOutcome;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompanyListsTest {

    private static final UUID P1 = UUID.randomUUID();
    private static final UUID P2 = UUID.randomUUID();

    private static CompanyListEntry e(String company, String type, UUID profile) {
        return new CompanyListEntry(UUID.randomUUID(), company, type, profile);
    }

    @Test
    void companiesCompareWithoutCaseAndExtraSpaces() {
        CompanyLists lists = new CompanyLists(List.of(e("Acme  Corp", "blocklist", null)));

        assertThat(lists.verdict(P1, "  acme corp ")).isEqualTo(ListVerdict.BLOCKED);
        assertThat(lists.verdict(P1, "Acme Corporation")).isEqualTo(ListVerdict.NONE);
        assertThat(lists.verdict(P1, null)).isEqualTo(ListVerdict.NONE);
        assertThat(lists.verdict(P1, "  ")).isEqualTo(ListVerdict.NONE);
    }

    @Test
    void aProfileEntryAppliesOnlyToThatProfileAndAGlobalOneToAll() {
        CompanyLists lists = new CompanyLists(List.of(e("Mine", "allowlist", P1), e("Everyone", "blocklist", null)));

        assertThat(lists.verdict(P1, "Mine")).isEqualTo(ListVerdict.ALLOWED);
        assertThat(lists.verdict(P2, "Mine")).isEqualTo(ListVerdict.NONE);
        assertThat(lists.verdict(P2, "Everyone")).isEqualTo(ListVerdict.BLOCKED);
    }

    @Test
    void aBlockWinsOverAnAllowInAnyScope() {
        CompanyLists lists = new CompanyLists(List.of(e("Acme", "allowlist", P1), e("Acme", "blocklist", null)));

        assertThat(lists.verdict(P1, "Acme")).isEqualTo(ListVerdict.BLOCKED);
    }

    private static final List<Criterion> ONE = List.of(new Criterion("a", "title", "contains_any", null, "prefer", 10, null, "A"));

    private static RuleResult satisfied(boolean on) {
        return new RuleResult(false, List.of(new CriterionResult("a", on ? Verdict.SATISFIED : Verdict.NOT_SATISFIED, on ? Effect.BONUS : Effect.NONE, 10)));
    }

    @Test
    void aBlockedCompanyIsFilteredOutWithAReasonAndNoScore() {
        ScoreOutcome o = Scorer.score(ONE, satisfied(true), ListVerdict.BLOCKED, 10);

        assertThat(o.hidden()).isTrue();
        assertThat(o.score()).isNull();
        assertThat(o.reasons()).extracting(MatchReason::kind).contains("blocklist");
    }

    @Test
    void anAllowedCompanyGetsTheBonusAndAReason() {
        ScoreOutcome plain = Scorer.score(ONE, satisfied(false), ListVerdict.NONE, 10);
        ScoreOutcome allowed = Scorer.score(ONE, satisfied(false), ListVerdict.ALLOWED, 10);

        assertThat(plain.score()).isZero();
        assertThat(allowed.score()).isEqualTo(50);
        assertThat(allowed.reasons().get(0)).isEqualTo(new MatchReason("company-allowlist", 10, "allowlist", "+10: the company is on your allowlist"));
    }

    @Test
    void anAllowlistBonusNeverLowersTheScore() {
        for (int rules = 0; rules <= 1; rules++) {
            ScoreOutcome without = Scorer.score(ONE, satisfied(rules == 1), ListVerdict.NONE, 10);
            ScoreOutcome with = Scorer.score(ONE, satisfied(rules == 1), ListVerdict.ALLOWED, 10);
            assertThat(with.score()).isGreaterThanOrEqualTo(without.score());
        }
    }
}
