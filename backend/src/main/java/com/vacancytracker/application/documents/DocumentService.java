package com.vacancytracker.application.documents;

import com.vacancytracker.application.ai.AiClient;
import com.vacancytracker.application.ai.AiService;
import com.vacancytracker.application.fit.ResumeFitService;
import com.vacancytracker.application.resume.Resume;
import com.vacancytracker.application.resume.ResumeService;
import com.vacancytracker.common.NotFoundException;
import com.vacancytracker.vacancy.Vacancy;
import com.vacancytracker.vacancy.VacancyService;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resumes and cover letters for one vacancy (T-083, ADR-042). A draft comes from Claude (only through
 * {@link AiService}, so only after the owner's opt-in) and is returned, not stored: the owner reads and edits it, and
 * saving stores it as the next numbered version. Without AI the owner starts from their resume and edits by hand.
 */
@Service
public class DocumentService {

    static final Set<String> KINDS = Set.of(TailoredDocument.RESUME, TailoredDocument.COVER_LETTER);
    static final int MAX_CONTENT = 100_000;

    /** A generated text the owner has not saved yet. */
    public record Draft(String kind, String content, String provider, String model) {
    }

    private final DocumentRepository repository;
    private final AiService ai;
    private final ResumeService resumes;
    private final ResumeFitService fits;
    private final VacancyService vacancies;
    private final Clock clock;

    public DocumentService(DocumentRepository repository, AiService ai, ResumeService resumes, ResumeFitService fits,
                           VacancyService vacancies, Clock clock) {
        this.repository = repository;
        this.ai = ai;
        this.resumes = resumes;
        this.fits = fits;
        this.vacancies = vacancies;
        this.clock = clock;
    }

    /** Asks Claude for a draft. Nothing is stored. */
    public Draft draft(UUID vacancyId, String kind, String instructions) {
        requireKind(kind);
        Vacancy vacancy = vacancies.get(vacancyId);
        Resume resume = resumes.findActive()
                .orElseThrow(() -> new IllegalArgumentException("Upload your resume first: the draft is written from it."));
        var fit = fits.fitFor(vacancyId).orElse(null);
        AiClient.AiPrompt prompt = TailoredDocument.RESUME.equals(kind)
                ? Prompts.resume(resume.text(), vacancy, fit, instructions)
                : Prompts.coverLetter(resume.text(), vacancy, fit, instructions);
        AiClient.AiReply reply = ai.complete(prompt);
        return new Draft(kind, reply.text(), reply.provider(), reply.model());
    }

    /** Stores the owner's text as the next version. origin is ai when it started from a draft, else manual. */
    @Transactional
    public TailoredDocument save(UUID vacancyId, String kind, String content, String origin, String provider, String model) {
        requireKind(kind);
        vacancies.get(vacancyId);
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("The document is empty.");
        }
        if (content.length() > MAX_CONTENT) {
            throw new IllegalArgumentException("The document is longer than " + MAX_CONTENT + " characters.");
        }
        boolean fromAi = "ai".equals(origin);
        return repository.insertNext(vacancyId, kind, content.strip(), fromAi ? "ai" : "manual",
                fromAi ? provider : null, fromAi ? model : null, clock.instant());
    }

    @Transactional(readOnly = true)
    public List<TailoredDocument> list(UUID vacancyId) {
        vacancies.get(vacancyId);
        return repository.forVacancy(vacancyId);
    }

    @Transactional(readOnly = true)
    public TailoredDocument get(UUID documentId) {
        return repository.find(documentId).orElseThrow(() -> new NotFoundException("Document not found: " + documentId));
    }

    private static void requireKind(String kind) {
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("kind must be resume or cover_letter");
        }
    }
}
