package com.vacancytracker.application.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vacancytracker.collection.mail.ImapMailReader;
import com.vacancytracker.collection.mail.MailParsers;
import com.vacancytracker.common.NotFoundException;
import com.vacancytracker.vacancy.ImportedPosting;
import com.vacancytracker.vacancy.Vacancy;
import com.vacancytracker.vacancy.VacancyService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

/** Adding one posting of a digest mail to the Pipeline, and marking the postings that are there already. */
class CorrespondenceAddToPipelineTest {

    private static final String URL = "https://www.linkedin.com/jobs/view/3912345678/";

    private final CorrespondenceRepository repository = mock(CorrespondenceRepository.class);
    private final VacancyService vacancies = mock(VacancyService.class);
    private final CorrespondenceService service = new CorrespondenceService(mock(ImapMailReader.class), mock(MailParsers.class), repository,
            vacancies, Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC), "INBOX", mock(MailSettings.class));
    private final UUID mailId = UUID.randomUUID();

    @AfterEach
    void stop() {
        service.shutdown();
    }

    private Correspondence mailWith(Correspondence.DigestJob... jobs) {
        Correspondence mail = new Correspondence(mailId, "k", "INBOX", "a@b.c", null, "s", null, null, "job_alert", null, null, null,
                "none", Instant.parse("2026-11-05T09:00:00Z"), List.of(jobs));
        when(repository.find(mailId)).thenReturn(Optional.of(mail));
        return mail;
    }

    @Test
    void createsTheVacancyFromThePostingWhenNoneHasTheLink() {
        mailWith(new Correspondence.DigestJob("Alpha", "Northwind", "Prague", URL));
        UUID id = UUID.randomUUID();
        Vacancy created = mock(Vacancy.class);
        when(created.getId()).thenReturn(id);
        when(vacancies.findIdByUrl(URL)).thenReturn(Optional.empty());
        when(vacancies.createImported(eq(URL), any())).thenReturn(created);

        CorrespondenceService.AddedJob added = service.addToPipeline(mailId, 0);

        assertThat(added).isEqualTo(new CorrespondenceService.AddedJob(id, true));
        ArgumentCaptor<ImportedPosting> posting = ArgumentCaptor.forClass(ImportedPosting.class);
        verify(vacancies).createImported(eq(URL), posting.capture());
        assertThat(posting.getValue().title()).isEqualTo("Alpha");
        assertThat(posting.getValue().company()).isEqualTo("Northwind");
        assertThat(posting.getValue().location()).isEqualTo("Prague");
    }

    @Test
    void answersTheExistingVacancyWithoutCreatingAnother() {
        mailWith(new Correspondence.DigestJob("Alpha", "Northwind", "Prague", URL));
        UUID existing = UUID.randomUUID();
        when(vacancies.findIdByUrl(URL)).thenReturn(Optional.of(existing));

        assertThat(service.addToPipeline(mailId, 0)).isEqualTo(new CorrespondenceService.AddedJob(existing, false));
        verify(vacancies, never()).createImported(any(), any());
    }

    @Test
    void aRaceWithAnotherInsertAnswersTheVacancyThatWon() {
        mailWith(new Correspondence.DigestJob("Alpha", "Northwind", "Prague", URL));
        UUID winner = UUID.randomUUID();
        when(vacancies.findIdByUrl(URL)).thenReturn(Optional.empty(), Optional.of(winner));
        when(vacancies.createImported(eq(URL), any())).thenThrow(new DuplicateKeyException("duplicate"));

        assertThat(service.addToPipeline(mailId, 0)).isEqualTo(new CorrespondenceService.AddedJob(winner, false));
    }

    @Test
    void anUnknownMailOrIndexIsNotFound() {
        mailWith(new Correspondence.DigestJob("Alpha", "Northwind", "Prague", URL));
        when(repository.find(any(UUID.class))).thenReturn(Optional.empty());
        when(repository.find(mailId)).thenReturn(Optional.of(new Correspondence(mailId, "k", "INBOX", null, null, null, null, null,
                "job_alert", null, null, null, "none", Instant.parse("2026-11-05T09:00:00Z"),
                List.of(new Correspondence.DigestJob("Alpha", "Northwind", "Prague", URL)))));

        assertThatThrownBy(() -> service.addToPipeline(UUID.randomUUID(), 0)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.addToPipeline(mailId, 1)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.addToPipeline(mailId, -1)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aPostingWithoutAWebLinkIsRefused() {
        mailWith(new Correspondence.DigestJob("A", "B", null, "javascript:alert(1)"), new Correspondence.DigestJob("A", "B", null, null));

        assertThatThrownBy(() -> service.addToPipeline(mailId, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.addToPipeline(mailId, 1)).isInstanceOf(IllegalArgumentException.class);
        verify(vacancies, never()).createImported(any(), any());
    }

    @Test
    void listMarksOnlyThePostingsThatAreVacancies() {
        UUID known = UUID.randomUUID();
        Correspondence mail = mailWith(new Correspondence.DigestJob("A", "B", null, URL),
                new Correspondence.DigestJob("C", "D", null, "https://x.example/2"), new Correspondence.DigestJob("E", "F", null, null));
        when(repository.list(null, false, 200)).thenReturn(List.of(mail));
        when(vacancies.findIdsByUrls(List.of(URL, "https://x.example/2"))).thenReturn(Map.of(URL, known));

        List<Correspondence.DigestJob> jobs = service.list(null, false).get(0).jobs();

        assertThat(jobs.get(0).vacancyId()).isEqualTo(known);
        assertThat(jobs.get(1).vacancyId()).isNull();
        assertThat(jobs.get(2).vacancyId()).isNull();
    }
}
