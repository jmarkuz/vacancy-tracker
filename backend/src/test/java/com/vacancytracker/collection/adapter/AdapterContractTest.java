package com.vacancytracker.collection.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.vacancytracker.collection.http.UpstreamException;
import com.vacancytracker.support.AbstractIntegrationTest;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Every adapter must pass these checks (T-030). Concrete tests supply an adapter wired to a local
 * fixture server, and the adapter's failure mode when the fixture returns an error.
 */
public abstract class AdapterContractTest extends AbstractIntegrationTest {

    /** An adapter that will successfully read its fixture. */
    protected abstract SourceAdapter healthyAdapter();

    /** An adapter whose fixture server answers with HTTP 503 for every request. */
    protected abstract SourceAdapter failingAdapter();

    @Test
    void hasStableNonBlankId() {
        assertThat(healthyAdapter().id()).isNotBlank();
        assertThat(healthyAdapter().id()).isEqualTo(healthyAdapter().id());
    }

    @Test
    void declaresServerSideFieldsAsKnownCriterionNames() {
        Set<String> fields = healthyAdapter().serverSideFields();
        assertThat(fields).isNotNull();
    }

    @Test
    void healthyFetchReturnsPostingsWithAdapterIdAndExternalId() {
        FetchOutcome outcome = healthyAdapter().fetch();
        for (RawPosting posting : outcome.postings()) {
            assertThat(posting.adapter()).isEqualTo(healthyAdapter().id());
            assertThat(posting.externalId()).isNotBlank();
            assertThat(posting.title()).isNotBlank();
        }
    }

    @Test
    void failingSourceRaisesUpstreamExceptionInsteadOfReturningPartialData() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> failingAdapter().fetch())
                .isInstanceOf(UpstreamException.class);
    }
}
