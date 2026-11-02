package com.vacancytracker.vacancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vacancytracker.support.AbstractIntegrationTest;
import com.vacancytracker.vacancy.BulkStatusService.Changed;
import com.vacancytracker.vacancy.BulkStatusService.Result;
import com.vacancytracker.vacancy.BulkStatusService.UndoItem;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class BulkStatusServiceTest extends AbstractIntegrationTest {

    @Autowired
    private BulkStatusService bulk;

    @Autowired
    private VacancyService vacancies;

    @Autowired
    private VacancyRepository repository;

    private UUID vacancy(String title) {
        return vacancies.create(title, "Acme", null, null).getId();
    }

    private UUID vacancyIn(String title, VacancyStatus status) {
        UUID id = vacancy(title);
        if (status != VacancyStatus.NEW) {
            vacancies.changeStatus(id, status, null);
        }
        return id;
    }

    private VacancyStatus status(UUID id) {
        return vacancies.get(id).getStatus();
    }

    @Test
    void changesWhatCanMoveSkipsTheRestWithAReasonAndRecordsEvents() {
        UUID a = vacancy("a");
        UUID b = vacancy("b");
        UUID rejected = vacancyIn("c", VacancyStatus.APPLIED);
        vacancies.changeStatus(rejected, VacancyStatus.REJECTED, null);
        UUID missing = UUID.randomUUID();

        Result r = bulk.change(List.of(a, b, rejected, missing), VacancyStatus.SAVED, "batch");

        assertThat(r.changed()).extracting(Changed::id).containsExactly(a, b);
        assertThat(r.changed()).extracting(Changed::from).containsOnly("New");
        assertThat(r.skipped()).extracting(BulkStatusService.Skipped::reason).containsExactly("Rejected cannot move to Saved", "Not found");
        assertThat(status(a)).isEqualTo(VacancyStatus.SAVED);
        assertThat(status(rejected)).isEqualTo(VacancyStatus.REJECTED);
        List<StatusEvent> history = vacancies.history(a);
        assertThat(history.get(history.size() - 1)).extracting(StatusEvent::to, StatusEvent::note).containsExactly(VacancyStatus.SAVED, "batch");
    }

    @Test
    void duplicateIdsChangeOnce() {
        UUID a = vacancy("a");

        Result r = bulk.change(List.of(a, a, a), VacancyStatus.SAVED, null);

        assertThat(r.changed()).hasSize(1);
        assertThat(vacancies.history(a)).hasSize(1);
    }

    @Test
    void undoRestoresStatusesAndTheUnseenFlagForTheWholeBatch() {
        UUID a = vacancy("a");
        UUID b = vacancyIn("b", VacancyStatus.SAVED);
        assertThat(vacancies.get(a).isNew()).isTrue();
        assertThat(vacancies.get(b).isNew()).isFalse();
        Result done = bulk.change(List.of(a, b), VacancyStatus.APPLIED, null);

        Result undone = bulk.undo(done.changed().stream().map(c -> new UndoItem(c.id(), c.from(), "Applied", c.wasNew())).toList());

        assertThat(undone.changed()).hasSize(2);
        assertThat(status(a)).isEqualTo(VacancyStatus.NEW);
        assertThat(status(b)).isEqualTo(VacancyStatus.SAVED);
        assertThat(vacancies.get(a).isNew()).isTrue();
        assertThat(vacancies.get(b).isNew()).isFalse();
        List<StatusEvent> history = vacancies.history(a);
        assertThat(history.get(history.size() - 1).note()).startsWith("undo");
        assertThat(history.get(history.size() - 1)).extracting(StatusEvent::from, StatusEvent::to)
                .containsExactly(VacancyStatus.APPLIED, VacancyStatus.NEW);
    }

    @Test
    void undoNeverOverwritesALaterChange() {
        UUID a = vacancy("a");
        UUID b = vacancy("b");
        Result done = bulk.change(List.of(a, b), VacancyStatus.SAVED, null);
        vacancies.changeStatus(a, VacancyStatus.APPLIED, null);

        Result undone = bulk.undo(done.changed().stream().map(c -> new UndoItem(c.id(), c.from(), "Saved", c.wasNew())).toList());

        assertThat(undone.changed()).extracting(Changed::id).containsExactly(b);
        assertThat(undone.skipped()).extracting(BulkStatusService.Skipped::id).containsExactly(a);
        assertThat(status(a)).isEqualTo(VacancyStatus.APPLIED);
        assertThat(status(b)).isEqualTo(VacancyStatus.NEW);
    }

    @Test
    void undoRefusesAClaimThatWasNeverTrue() {
        UUID a = vacancy("a");
        bulk.change(List.of(a), VacancyStatus.SAVED, null);

        Result undone = bulk.undo(List.of(new UndoItem(a, "Offer", "Saved", false)));

        assertThat(undone.changed()).isEmpty();
        assertThat(status(a)).isEqualTo(VacancyStatus.SAVED);
    }

    @Test
    void undoingTwiceDoesNothingTheSecondTime() {
        UUID a = vacancy("a");
        bulk.change(List.of(a), VacancyStatus.SAVED, null);
        List<UndoItem> items = List.of(new UndoItem(a, "New", "Saved", true));

        assertThat(bulk.undo(items).changed()).hasSize(1);
        assertThat(bulk.undo(items).changed()).isEmpty();
        assertThat(status(a)).isEqualTo(VacancyStatus.NEW);
    }

    @Test
    void aBatchOverTheLimitIsRejected() {
        List<UUID> ids = IntStream.range(0, BulkStatusService.MAX_BATCH + 1).mapToObj(i -> UUID.randomUUID()).toList();

        assertThatThrownBy(() -> bulk.change(ids, VacancyStatus.SAVED, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBigBatchChangesEveryVacancy() {
        List<UUID> ids = IntStream.range(0, 300).mapToObj(i -> vacancy("v" + i)).toList();

        Result r = bulk.change(ids, VacancyStatus.DISMISSED, null);

        assertThat(r.changed()).hasSize(300);
        assertThat(r.skipped()).isEmpty();
        assertThat(repository.findById(ids.get(299)).orElseThrow().status()).isEqualTo(VacancyStatus.DISMISSED);
    }
}
