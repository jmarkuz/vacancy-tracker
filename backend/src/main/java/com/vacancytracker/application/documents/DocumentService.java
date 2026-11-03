package com.vacancytracker.application.documents;

import com.vacancytracker.application.ai.AiClient;
import com.vacancytracker.application.ai.AiService;
import com.vacancytracker.application.preset.Placeholders;
import com.vacancytracker.application.preset.Preset;
import com.vacancytracker.application.preset.PresetService;
import com.vacancytracker.application.resume.ResumeRepository;
import com.vacancytracker.application.resume.ResumeService;
import com.vacancytracker.common.NotFoundException;
import com.vacancytracker.vacancy.Vacancy;
import com.vacancytracker.vacancy.VacancyService;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resumes and cover letters for one vacancy (T-083, T-094, ADR-042, ADR-048). AI never writes a document from
 * scratch: it adapts the base draft of a preset, under the guardrail, and only through {@link AiService} (so only
 * after the owner's opt-in). The draft is returned, not stored: the owner reads and edits it, and saving stores the
 * next numbered version together with a snapshot of what it was made from. "Use as is" stores the base draft itself,
 * without AI.
 */
@Service
public class DocumentService {

    static final Set<String> KINDS = Set.of(TailoredDocument.RESUME, TailoredDocument.COVER_LETTER);
    static final int MAX_CONTENT = 100_000;

    /**
     * A generated text the owner has not saved yet, with the base draft it was adapted from. For a resume, pages is the
     * length of its PDF and shortened how many times AI shortened it to fit two pages; pageProblem says why it still
     * does not fit, when it does not.
     */
    public record Draft(String kind, String content, String provider, String model, UUID snapshotId, String baseDraft,
                        String presetName, Integer pages, int shortened, String pageProblem, List<FactCheck.Finding> facts) {
    }

    /** A shortened resume and its pages. */
    public record Shortened(String content, int pages, int attempts) {
    }

    static final int MAX_SHORTENING = 2;

    private final DocumentRepository repository;
    private final SnapshotRepository snapshots;
    private final AiService ai;
    private final PresetService presets;
    private final VacancyService vacancies;
    private final ResumeService resumes;
    private final Clock clock;

    public DocumentService(DocumentRepository repository, SnapshotRepository snapshots, AiService ai, PresetService presets,
                           VacancyService vacancies, ResumeService resumes, Clock clock) {
        this.repository = repository;
        this.snapshots = snapshots;
        this.ai = ai;
        this.presets = presets;
        this.vacancies = vacancies;
        this.resumes = resumes;
        this.clock = clock;
    }

    /** Asks Claude to adapt the preset's base draft (the default preset when presetId is null). Nothing is stored but the snapshot. */
    public Draft draft(UUID vacancyId, String kind, UUID presetId, String instructions) {
        requireKind(kind);
        Vacancy vacancy = vacancies.get(vacancyId);
        GenerationSnapshot s = snapshot(vacancyId, kind, presetId, true);
        AiClient.AiReply reply = ai.complete(Prompts.adapt(s, vacancy, instructions));
        snapshots.insert(s);
        if (!TailoredDocument.RESUME.equals(kind)) {
            return new Draft(kind, reply.text(), reply.provider(), reply.model(), s.id(), s.baseDraft(), s.presetName(), null, 0, null,
                    facts(s, vacancy, reply.text()));
        }
        // Over two pages: AI shortens the least relevant content, at most twice; then the owner edits.
        String text = reply.text();
        int pages = ResumePdf.render(text).pages();
        int shortened = 0;
        while (pages > ResumePdf.MAX_PAGES && shortened < MAX_SHORTENING) {
            text = ai.complete(Prompts.shorten(s.guardrail(), vacancy, text, pages)).text();
            pages = ResumePdf.render(text).pages();
            shortened++;
        }
        return new Draft(kind, text, reply.provider(), reply.model(), s.id(), s.baseDraft(), s.presetName(), pages, shortened,
                pages > ResumePdf.MAX_PAGES ? tooLong(pages, shortened) : null, facts(s, vacancy, text));
    }

    /** The fact check of a text against the base documents of its snapshot (T-098). */
    public List<FactCheck.Finding> checkFacts(UUID vacancyId, UUID snapshotId, String text) {
        Vacancy vacancy = vacancies.get(vacancyId);
        GenerationSnapshot s = snapshots.find(snapshotId).filter(x -> x.vacancyId().equals(vacancyId))
                .orElseThrow(() -> new IllegalArgumentException("Unknown snapshot."));
        return facts(s, vacancy, text);
    }

    private static List<FactCheck.Finding> facts(GenerationSnapshot s, Vacancy vacancy, String text) {
        String base = s.baseResume() == null ? s.baseDraft() : s.baseDraft() + "\n\n" + s.baseResume();
        return FactCheck.check(base, text, vacancy.getCompany(), vacancy.getTitle());
    }

    /** Asks AI to shorten a resume that does not fit two pages, at most twice. */
    public Shortened shorten(UUID vacancyId, String text) {
        Vacancy vacancy = vacancies.get(vacancyId);
        int pages = ResumePdf.render(text).pages();
        int attempts = 0;
        while (pages > ResumePdf.MAX_PAGES && attempts < MAX_SHORTENING) {
            text = ai.complete(Prompts.shorten(presets.defaults().guardrail(), vacancy, text, pages)).text();
            pages = ResumePdf.render(text).pages();
            attempts++;
        }
        if (pages > ResumePdf.MAX_PAGES) {
            throw new ResumeTooLongException(pages, tooLong(pages, attempts));
        }
        return new Shortened(text, pages, attempts);
    }

    private static String tooLong(int pages, int attempts) {
        return "The resume PDF has " + pages + " pages" + (attempts > 0 ? " after " + attempts + " shortening attempts" : "")
                + "; at most " + ResumePdf.MAX_PAGES + " are allowed. Shorten it by hand: remove older or less relevant roles and bullets.";
    }

    /** The PDF of a resume version, rendered from its text; over two pages it is refused, so nothing too long is kept. */
    private static ResumePdf.Rendered pdfOf(String text) {
        ResumePdf.Rendered pdf = ResumePdf.render(text);
        if (pdf.pages() > ResumePdf.MAX_PAGES) {
            throw new ResumeTooLongException(pdf.pages(), tooLong(pdf.pages(), 0));
        }
        return pdf;
    }

    /** Stores the preset's base draft as the next version, unchanged. Works with AI off. */
    @Transactional
    public TailoredDocument useAsIs(UUID vacancyId, String kind, UUID presetId) {
        requireKind(kind);
        vacancies.get(vacancyId);
        GenerationSnapshot s = snapshot(vacancyId, kind, presetId, false);
        ResumePdf.Rendered pdf = TailoredDocument.RESUME.equals(kind) ? asIsPdf(s) : null;
        snapshots.insert(s);
        return repository.insertNext(vacancyId, kind, s.baseDraft(), TailoredDocument.BASE, null, null, clock.instant(), s.id(), pdf,
                List.of(), false);
    }

    /** An uploaded PDF of one or two pages is used unchanged; otherwise the PDF is rendered from the text. */
    private ResumePdf.Rendered asIsPdf(GenerationSnapshot s) {
        Optional<ResumeRepository.Original> original = resumes.originalOf(s.baseResumeId());
        if (original.isPresent() && isPdf(original.get())) {
            int pages = ResumePdf.pages(original.get().bytes());
            if (pages >= 1 && pages <= ResumePdf.MAX_PAGES) {
                return new ResumePdf.Rendered(original.get().bytes(), pages);
            }
            if (pages > ResumePdf.MAX_PAGES) {
                throw new ResumeTooLongException(pages, "Your uploaded resume PDF has " + pages + " pages; at most " + ResumePdf.MAX_PAGES
                        + " are allowed. Upload a shorter one on the Presets screen, or generate an adapted version with AI.");
            }
        }
        return pdfOf(s.baseDraft());
    }

    public static boolean isPdf(ResumeRepository.Original original) {
        return original.fileName() != null && original.fileName().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")
                || "application/pdf".equalsIgnoreCase(original.contentType());
    }

    /**
     * The PDF of a resume version: the stored one, or, for a version saved before PDFs were kept, one rendered now
     * (refused when it would not fit two pages). Cover letters have none.
     */
    @Transactional(readOnly = true)
    public byte[] pdf(TailoredDocument doc) {
        if (!TailoredDocument.RESUME.equals(doc.kind())) {
            throw new IllegalArgumentException("A PDF is made for resumes only.");
        }
        return repository.pdf(doc.id()).orElseGet(() -> pdfOf(doc.content()).bytes());
    }

    /**
     * Stores the owner's text as the next version. origin is ai when it started from a draft, else manual; snapshotId
     * links it to what the draft was made from (also for an edited copy of such a version).
     */
    @Transactional
    public TailoredDocument save(UUID vacancyId, String kind, String content, String origin, String provider, String model,
                                 UUID snapshotId, boolean factsConfirmed) {
        requireKind(kind);
        Vacancy vacancy = vacancies.get(vacancyId);
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("The document is empty.");
        }
        if (content.length() > MAX_CONTENT) {
            throw new IllegalArgumentException("The document is longer than " + MAX_CONTENT + " characters.");
        }
        List<FactCheck.Finding> findings = null;
        if (snapshotId != null) {
            GenerationSnapshot s = snapshots.find(snapshotId).orElseThrow(() -> new IllegalArgumentException("Unknown snapshot."));
            if (!s.vacancyId().equals(vacancyId) || !s.kind().equals(kind)) {
                throw new IllegalArgumentException("That draft belongs to another vacancy or document.");
            }
            // Saving, and so the PDF, waits until new facts are fixed or the owner confirms they are true (T-098).
            findings = facts(s, vacancy, content.strip());
            if (!findings.isEmpty() && !factsConfirmed) {
                throw new FactsNotConfirmedException(findings);
            }
        }
        boolean fromAi = TailoredDocument.AI.equals(origin);
        ResumePdf.Rendered pdf = TailoredDocument.RESUME.equals(kind) ? pdfOf(content.strip()) : null;
        return repository.insertNext(vacancyId, kind, content.strip(), fromAi ? TailoredDocument.AI : TailoredDocument.MANUAL,
                fromAi ? provider : null, fromAi ? model : null, clock.instant(), snapshotId, pdf, findings,
                findings != null && !findings.isEmpty() && factsConfirmed);
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

    /** What the version was made from; empty for versions without a snapshot. */
    @Transactional(readOnly = true)
    public Optional<GenerationSnapshot> snapshotOf(TailoredDocument doc) {
        return doc.snapshotId() == null ? Optional.empty() : snapshots.find(doc.snapshotId());
    }

    /** Copies what the document is made from out of the preset. forAi also needs the goal and the prompt filled in. */
    private GenerationSnapshot snapshot(UUID vacancyId, String kind, UUID presetId, boolean forAi) {
        Preset p = presets.getOrDefault(presetId);
        PresetService.BaseResume base = presets.baseResume(p);
        boolean resume = TailoredDocument.RESUME.equals(kind);
        if (forAi) {
            PresetService.Readiness readiness = presets.readiness(p, base);
            List<PresetService.Missing> missing = resume ? readiness.resume() : readiness.coverLetter();
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException("The preset \"" + p.name() + "\" is not ready for AI: "
                        + missing.stream().map(PresetService.Missing::message).collect(Collectors.joining(" ")) + " Complete it on the Presets screen.");
            }
        } else if (resume ? base.template() || !Placeholders.of(base.text()).isEmpty() : !Placeholders.of(p.baseCoverLetter()).isEmpty()) {
            throw new IllegalArgumentException("Fill in the base " + (resume ? "resume" : "cover letter") + " of the preset \""
                    + p.name() + "\" on the Presets screen first: it still has the template's placeholders.");
        }
        boolean baseResumeUsable = !base.template() && Placeholders.of(base.text()).isEmpty();
        return new GenerationSnapshot(UUID.randomUUID(), vacancyId, kind, p.id(), p.name(), presets.defaults().guardrail(), p.mainGoal(),
                p.weights(), resume ? p.resumePrompt() : p.coverLetterPrompt(), resume ? base.text() : p.baseCoverLetter(),
                !resume && baseResumeUsable ? base.text() : null, resume ? base.resumeId() : null, clock.instant());
    }

    private static void requireKind(String kind) {
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("kind must be resume or cover_letter");
        }
    }
}
