package com.vacancytracker.collection.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.vacancytracker.support.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Forgetting what was handled, by prefix: the mail tracker's marks go, the alert sources' stay. */
@SpringBootTest
class ProcessedMailRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private ProcessedMailRepository repository;

    @Test
    void forgetsOnlyTheKeysWithThePrefix() {
        repository.markProcessed("reply:<a@x>");
        repository.markProcessed("reply:<b@x>");
        repository.markProcessed("<alert@x>");

        int forgotten = repository.forgetWithPrefix("reply:");

        assertThat(forgotten).isEqualTo(2);
        assertThat(repository.known(List.of("reply:<a@x>", "reply:<b@x>", "<alert@x>"))).containsExactly("<alert@x>");
    }

    @Test
    void aPrefixIsNotAPattern() {
        repository.markProcessed("reply_x");
        repository.markProcessed("replyYx");

        assertThat(repository.forgetWithPrefix("reply_")).isEqualTo(1);
        assertThat(repository.known(List.of("reply_x", "replyYx"))).containsExactly("replyYx");
    }

    @Test
    void forgettingWhenNothingMatchesChangesNothing() {
        repository.markProcessed("<keep@x>");

        assertThat(repository.forgetWithPrefix("reply:")).isZero();
        assertThat(repository.known(List.of("<keep@x>"))).containsExactly("<keep@x>");
    }
}
