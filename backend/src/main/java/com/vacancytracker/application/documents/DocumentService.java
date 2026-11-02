package com.vacancytracker.application.documents;

import com.vacancytracker.application.ai.AiClient;
import com.vacancytracker.application.ai.AiService;
import com.vacancytracker.application.preset.Placeholders;
import com.vacancytracker.application.preset.Preset;
import com.vacancytracker.application.preset.PresetService;
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

    /** A generated text the owner has not saved yet, with the base draft it was adapted from. */
    public record Draft(String kind, String content, String provider, String model, UUID snapshotId, String baseDraft,
                        String presetName) {
    }

    private final DocumentRepository repository;
    private final SnapshotRepository snapshots;
    private final AiService ai;
    private final PresetService presets;
    private final VacancyService vacancies;
    private final Clock clock;

    public DocumentService(DocumentRepository repository, SnapshotRepository snapshots, AiService ai, PresetService presets,
                           VacancyService vacancies, Clock clock) {
        this.repository = repository;
        this.snapshots = snapshots;
        this.ai = ai;
        this.presets = presets;
        this.vacancies = vacancies;
        this.clock = clock;
    }

    /** Asks Claude to adapt the preset's base draft (the default preset when presetId is null). Nothing is stored but the snapshot. */
    public Draft draft(UUID vacancyId, String kind, UUID presetId, String instructions) {
        requireKind(kind);
        Vacancy vacancy = vacancies.get(vacancyId);
        GenerationSnapshot s = snapshot(vacancyId, kind, presetId, true);
        AiClient.AiReply reply = ai.complete(Prompts.adapt(s, vacancy, instructions));
        snapshots.insert(s);
        return new Draft(kind, reply.text(), reply.provider(), reply.model(), s.id(), s.baseDraft(), s.presetName());
    }

    /** Stores the preset's base draft as the next version, unchanged. Works with AI off. */
    @Transactional
    public TailoredDocument useAsIs(UUID vacancyId, String kind, UUID presetId) {
        requireKind(kind);
        vacancies.get(vacancyId);
        GenerationSnapshot s = snapshot(vacancyId, kind, presetId, false);
        snapshots.insert(s);
        return repository.insertNext(vacancyId, kind, s.baseDraft(), TailoredDocument.BASE, null, null, clock.instant(), s.id());
    }

    /**
     * Stores the owner's text as the next version. origin is ai when it started from a draft, else manual; snapshotId
     * links it to what the draft was made from (also for an edited copy of such a version).
     */
    @Transactional
    public TailoredDocument save(UUID vacancyId, String kind, String content, String origin, String provider, String model,
                                 UUID snapshotId) {
        requireKind(kind);
        vacancies.get(vacancyId);
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("The document is empty.");
        }
        if (content.length() > MAX_CONTENT) {
            throw new IllegalArgumentException("The document is longer than " + MAX_CONTENT + " characters.");
        }
        if (snapshotId != null) {
            GenerationSnapshot s = snapshots.find(snapshotId).orElseThrow(() -> new IllegalArgumentException("Unknown snapshot."));
            if (!s.vacancyId().equals(vacancyId) || !s.kind().equals(kind)) {
                throw new IllegalArgumentException("That draft belongs to another vacancy or document.");
            }
        }
        boolean fromAi = TailoredDocument.AI.equals(origin);
        return repository.insertNext(vacancyId, kind, content.strip(), fromAi ? TailoredDocument.AI : TailoredDocument.MANUAL,
                fromAi ? provider : null, fromAi ? model : null, clock.instant(), snapshotId);
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
