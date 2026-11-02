package com.vacancytracker.application.preset;

import com.vacancytracker.application.resume.Resume;
import com.vacancytracker.application.resume.ResumeService;
import com.vacancytracker.application.resume.ResumeTextExtractor;
import com.vacancytracker.common.NotFoundException;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI presets (T-093, ADR-047). There is always at least one preset, and exactly one is the default; a preset named
 * "Default" filled from {@link PresetDefaults} is created on first use, with the active resume (if any) as its base
 * resume. The base resume is the resume table's row, shared with the Resume screen: the active resume is the default
 * preset's base resume, so uploading on either screen changes the same thing.
 */
@Service
public class PresetService {

    public static final String DEFAULT_NAME = "Default";
    static final int MAX_NAME = 100;
    static final int MAX_TEXT = 50_000;
    static final int MAX_WEIGHTS = 20;
    static final int MAX_WEIGHT = 1000;
    static final int MAX_PARAMETER_NAME = 80;

    /** The base resume as the preset sees it: a stored resume, or the default template while there is none. */
    public record BaseResume(UUID resumeId, String name, String fileName, String contentType, String text, boolean template,
                             boolean hasOriginal) {
    }

    /** A field that keeps a document from being generated, with the placeholders still to fill. */
    public record Missing(String field, String message, List<String> placeholders) {
    }

    /** What is still missing before each document can be generated with this preset. */
    public record Readiness(List<Missing> resume, List<Missing> coverLetter) {

        public boolean resumeReady() {
            return resume.isEmpty();
        }

        public boolean coverLetterReady() {
            return coverLetter.isEmpty();
        }
    }

    /** A preset with what the screen shows next to it. */
    public record Details(Preset preset, List<Double> shares, BaseResume baseResume, Readiness readiness) {
    }

    /** Changes to a preset; a null field is left as it is. */
    public record Update(String name, String mainGoal, List<Preset.Weight> weights, String resumePrompt, String coverLetterPrompt,
                         String baseCoverLetter) {
    }

    private final PresetRepository repository;
    private final PresetDefaults defaults;
    private final ResumeService resumes;
    private final Clock clock;

    public PresetService(PresetRepository repository, PresetDefaults defaults, ResumeService resumes, Clock clock) {
        this.repository = repository;
        this.defaults = defaults;
        this.resumes = resumes;
        this.clock = clock;
    }

    /** A resume uploaded or edited on the Resume screen becomes the default preset's base resume. */
    @PostConstruct
    void listen() {
        resumes.addChangeListener(resume -> {
            if (resume != null) {
                repository.findDefault().ifPresent(p -> repository.setBaseResume(p.id(), resume.id()));
            }
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    void onStart() {
        ensureDefault();
    }

    /** Creates the Default preset when there is none, and makes sure one preset is the default. */
    @Transactional
    public void ensureDefault() {
        if (repository.count() == 0) {
            Instant now = clock.instant();
            repository.insertIfAbsent(new Preset(UUID.randomUUID(), DEFAULT_NAME, true, defaults.mainGoal(), defaults.weights(),
                    defaults.resumePrompt(), defaults.coverLetterPrompt(), resumes.findActive().map(Resume::id).orElse(null),
                    defaults.baseCoverLetter(), null, now, now));
        } else if (repository.findDefault().isEmpty()) {
            repository.findAll().stream().findFirst().ifPresent(p -> repository.makeDefault(p.id()));
        }
    }

    @Transactional
    public List<Preset> list() {
        ensureDefault();
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public Preset get(UUID id) {
        return repository.find(id).orElseThrow(() -> new NotFoundException("Preset not found: " + id));
    }

    @Transactional
    public Preset getDefault() {
        ensureDefault();
        return repository.findDefault().orElseThrow(() -> new IllegalStateException("No default preset"));
    }

    /** The given preset, or the default one when id is null. */
    @Transactional
    public Preset getOrDefault(UUID id) {
        return id == null ? getDefault() : get(id);
    }

    /** A new preset filled with the defaults. */
    @Transactional
    public Preset create(String name) {
        ensureDefault();
        String clean = validName(name, null);
        Instant now = clock.instant();
        Preset p = new Preset(UUID.randomUUID(), clean, false, defaults.mainGoal(), defaults.weights(), defaults.resumePrompt(),
                defaults.coverLetterPrompt(), null, defaults.baseCoverLetter(), null, now, now);
        repository.insert(p);
        return p;
    }

    /** A copy of a preset, base documents included (the copy shares the stored base resume). */
    @Transactional
    public Preset duplicate(UUID id, String name) {
        Preset source = get(id);
        String clean = name == null || name.isBlank() ? freeName(source.name() + " copy") : validName(name, null);
        Instant now = clock.instant();
        Preset copy = new Preset(UUID.randomUUID(), clean, false, source.mainGoal(), source.weights(), source.resumePrompt(),
                source.coverLetterPrompt(), source.baseResumeId(), source.baseCoverLetter(), source.baseCoverLetterFileName(), now, now);
        repository.insert(copy);
        return copy;
    }

    @Transactional
    public Preset update(UUID id, Update change) {
        Preset p = get(id);
        Preset updated = new Preset(p.id(),
                change.name() == null ? p.name() : validName(change.name(), p.id()),
                p.isDefault(),
                change.mainGoal() == null ? p.mainGoal() : validText(change.mainGoal(), "Main goal prompt"),
                change.weights() == null ? p.weights() : validWeights(change.weights()),
                change.resumePrompt() == null ? p.resumePrompt() : validText(change.resumePrompt(), "Resume prompt"),
                change.coverLetterPrompt() == null ? p.coverLetterPrompt() : validText(change.coverLetterPrompt(), "Cover letter prompt"),
                p.baseResumeId(),
                change.baseCoverLetter() == null ? p.baseCoverLetter() : validText(change.baseCoverLetter(), "Base cover letter"),
                change.baseCoverLetter() == null ? p.baseCoverLetterFileName() : null,
                p.createdAt(), clock.instant());
        repository.update(updated);
        return updated;
    }

    /** The last preset cannot be deleted; deleting the default makes the next preset the default. */
    @Transactional
    public void delete(UUID id) {
        Preset p = get(id);
        if (repository.count() <= 1) {
            throw new IllegalArgumentException("The last preset cannot be deleted.");
        }
        repository.delete(id);
        if (p.isDefault()) {
            Preset next = repository.findAll().get(0);
            makeDefault(next.id());
        }
    }

    /** Makes the preset the default; its base resume becomes the active resume. */
    @Transactional
    public Preset makeDefault(UUID id) {
        Preset p = get(id);
        repository.makeDefault(id);
        resumes.activate(p.baseResumeId());
        return get(id);
    }

    @Transactional
    public Preset uploadBaseResume(UUID id, String fileName, String contentType, byte[] bytes) {
        Preset p = get(id);
        Resume resume = resumes.uploadBase(fileName, contentType, bytes, p.isDefault());
        return linkResume(p, resume.id());
    }

    @Transactional
    public Preset pasteBaseResume(UUID id, String text) {
        Preset p = get(id);
        Resume resume = resumes.pasteBase(p.name() + " base resume", text, p.isDefault());
        return linkResume(p, resume.id());
    }

    /** Back to the default template. For the default preset this leaves no active resume, as the screens share it. */
    @Transactional
    public Preset resetBaseResume(UUID id) {
        Preset p = get(id);
        Preset updated = linkResume(p, null);
        if (p.isDefault()) {
            resumes.activate(null);
        }
        return updated;
    }

    @Transactional
    public Preset uploadBaseCoverLetter(UUID id, String fileName, byte[] bytes) {
        Preset p = get(id);
        String text = ResumeTextExtractor.extract(fileName, bytes);
        Preset updated = new Preset(p.id(), p.name(), p.isDefault(), p.mainGoal(), p.weights(), p.resumePrompt(),
                p.coverLetterPrompt(), p.baseResumeId(), validText(text, "Base cover letter"), fileName, p.createdAt(), clock.instant());
        repository.update(updated);
        return updated;
    }

    private Preset linkResume(Preset p, UUID resumeId) {
        Preset updated = new Preset(p.id(), p.name(), p.isDefault(), p.mainGoal(), p.weights(), p.resumePrompt(),
                p.coverLetterPrompt(), resumeId, p.baseCoverLetter(), p.baseCoverLetterFileName(), p.createdAt(), clock.instant());
        repository.update(updated);
        return updated;
    }

    @Transactional(readOnly = true)
    public Details details(Preset p) {
        BaseResume base = baseResume(p);
        return new Details(p, p.shares(), base, readiness(p, base));
    }

    /** The preset's base resume, or the default template while it has none. */
    @Transactional(readOnly = true)
    public BaseResume baseResume(Preset p) {
        return resumes.find(p.baseResumeId())
                .map(r -> new BaseResume(r.id(), r.name(), r.fileName(), r.contentType(), r.text(), false, r.fileName() != null))
                .orElseGet(() -> new BaseResume(null, "Default template", null, "text/markdown", defaults.baseResume(), true, false));
    }

    public Readiness readiness(Preset p, BaseResume base) {
        List<Missing> common = new ArrayList<>();
        check(common, "mainGoal", "Main goal prompt", p.mainGoal());
        if (p.weights().stream().mapToInt(Preset.Weight::weight).sum() == 0) {
            common.add(new Missing("weights", "Give at least one parameter a weight above zero.", List.of()));
        }
        List<Missing> resume = new ArrayList<>(common);
        check(resume, "resumePrompt", "Resume prompt", p.resumePrompt());
        if (base.template()) {
            resume.add(new Missing("baseResume", "Base resume: upload or paste your resume.", Placeholders.of(base.text())));
        } else {
            check(resume, "baseResume", "Base resume", base.text());
        }
        List<Missing> letter = new ArrayList<>(common);
        check(letter, "coverLetterPrompt", "Cover letter prompt", p.coverLetterPrompt());
        check(letter, "baseCoverLetter", "Base cover letter", p.baseCoverLetter());
        return new Readiness(List.copyOf(resume), List.copyOf(letter));
    }

    private static void check(List<Missing> into, String field, String label, String text) {
        List<String> marks = Placeholders.of(text);
        if (!marks.isEmpty()) {
            into.add(new Missing(field, label + ": fill in " + marks.size() + (marks.size() == 1 ? " placeholder." : " placeholders."), marks));
        } else if (text == null || text.isBlank()) {
            into.add(new Missing(field, label + " is empty.", List.of()));
        }
    }

    private String validName(String name, UUID self) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("The preset needs a name.");
        }
        String clean = name.strip().replaceAll("\\s+", " ");
        if (clean.length() > MAX_NAME) {
            throw new IllegalArgumentException("The name is longer than " + MAX_NAME + " characters.");
        }
        repository.findByName(clean).filter(other -> !other.id().equals(self)).ifPresent(other -> {
            throw new IllegalArgumentException("A preset named \"" + other.name() + "\" already exists.");
        });
        return clean;
    }

    private String freeName(String base) {
        String name = base.length() > MAX_NAME - 4 ? base.substring(0, MAX_NAME - 4) : base;
        String candidate = name;
        for (int i = 2; repository.findByName(candidate).isPresent(); i++) {
            candidate = name + " " + i;
        }
        return candidate;
    }

    private static String validText(String text, String label) {
        String clean = text.replace("\r\n", "\n").strip();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException(label + " is empty. Use Reset to default to start again.");
        }
        if (clean.length() > MAX_TEXT) {
            throw new IllegalArgumentException(label + " is longer than " + MAX_TEXT + " characters.");
        }
        return clean;
    }

    static List<Preset.Weight> validWeights(List<Preset.Weight> weights) {
        if (weights.isEmpty() || weights.size() > MAX_WEIGHTS) {
            throw new IllegalArgumentException("A preset needs between 1 and " + MAX_WEIGHTS + " parameters.");
        }
        Set<String> seen = new HashSet<>();
        List<Preset.Weight> clean = new ArrayList<>();
        for (Preset.Weight w : weights) {
            if (w == null || w.name() == null || w.name().isBlank()) {
                throw new IllegalArgumentException("Every parameter needs a name.");
            }
            String name = w.name().strip().replaceAll("\\s+", " ");
            if (name.length() > MAX_PARAMETER_NAME) {
                throw new IllegalArgumentException("A parameter name is longer than " + MAX_PARAMETER_NAME + " characters.");
            }
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("The parameter \"" + name + "\" is listed twice.");
            }
            if (w.weight() < 0 || w.weight() > MAX_WEIGHT) {
                throw new IllegalArgumentException("A weight must be between 0 and " + MAX_WEIGHT + ".");
            }
            clean.add(new Preset.Weight(name, w.weight()));
        }
        if (clean.stream().mapToInt(Preset.Weight::weight).sum() == 0) {
            throw new IllegalArgumentException("Give at least one parameter a weight above zero.");
        }
        return List.copyOf(clean);
    }

    public PresetDefaults defaults() {
        return defaults;
    }
}
