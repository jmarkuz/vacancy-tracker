package com.vacancytracker.application.resume;

import com.vacancytracker.common.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The user's resume (T-080, ADR-040). Uploading replaces the active resume; its keywords are proposed from the text
 * and the user edits them. Every change notifies the listeners (the fit recalculation) after it is stored.
 */
@Service
public class ResumeService {

    static final int MAX_KEYWORDS = 60;
    static final int PROPOSED_KEYWORDS = 40;
    static final int MAX_KEYWORD_LENGTH = 60;

    private final ResumeRepository repository;
    private final Clock clock;
    private final List<Consumer<Resume>> listeners = new CopyOnWriteArrayList<>();

    public ResumeService(ResumeRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Called with the active resume after each change, and with null after it is deleted. Listeners must be quick. */
    public void addChangeListener(Consumer<Resume> listener) {
        listeners.add(listener);
    }

    /** Reads an uploaded file and makes it the active resume. */
    @Transactional
    public Resume upload(String fileName, String contentType, byte[] bytes) {
        String text = ResumeTextExtractor.extract(fileName, bytes);
        return store(displayName(fileName), fileName, contentType, text, bytes);
    }

    /** Makes pasted text the active resume. */
    @Transactional
    public Resume paste(String name, String text) {
        String clean = text == null ? "" : ResumeTextExtractor.normalize(text);
        if (clean.isBlank()) {
            throw new UnsupportedResumeException("The resume text is empty.");
        }
        return store(name == null || name.isBlank() ? "Pasted resume" : name.strip(), null, "text/plain", clean, null);
    }

    /** Reads an uploaded file as a preset's base resume (T-093); it becomes the active resume only when active. */
    @Transactional
    public Resume uploadBase(String fileName, String contentType, byte[] bytes, boolean active) {
        String text = ResumeTextExtractor.extract(fileName, bytes);
        return store(displayName(fileName), fileName, contentType, text, bytes, active);
    }

    /** Pasted text as a preset's base resume; it becomes the active resume only when active. */
    @Transactional
    public Resume pasteBase(String name, String text, boolean active) {
        String clean = text == null ? "" : ResumeTextExtractor.normalize(text);
        if (clean.isBlank()) {
            throw new UnsupportedResumeException("The resume text is empty.");
        }
        return store(name == null || name.isBlank() ? "Pasted resume" : name.strip(), null, "text/plain", clean, null, active);
    }

    /** Makes a stored resume the active one (the default preset's base resume); null leaves none active. */
    @Transactional
    public void activate(UUID id) {
        Optional<Resume> before = repository.findActive();
        if (before.map(Resume::id).equals(Optional.ofNullable(id))) {
            return;
        }
        repository.activate(id);
        notifyListeners(id == null ? null : repository.find(id).orElse(null));
    }

    @Transactional(readOnly = true)
    public Optional<Resume> find(UUID id) {
        return id == null ? Optional.empty() : repository.find(id);
    }

    /** The uploaded file of any stored resume, when it was uploaded rather than pasted. */
    @Transactional(readOnly = true)
    public Optional<ResumeRepository.Original> originalOf(UUID id) {
        return id == null ? Optional.empty() : repository.findOriginal(id);
    }

    private Resume store(String name, String fileName, String contentType, String text, byte[] original) {
        return store(name, fileName, contentType, text, original, true);
    }

    private Resume store(String name, String fileName, String contentType, String text, byte[] original, boolean active) {
        Instant now = clock.instant();
        Resume resume = new Resume(UUID.randomUUID(), name, fileName, contentType, text,
                Terms.resumeKeywords(text, PROPOSED_KEYWORDS), 1, now, now);
        repository.insert(resume, original, active);
        if (active) {
            notifyListeners(resume);
        }
        return resume;
    }

    @Transactional(readOnly = true)
    public Resume active() {
        return repository.findActive().orElseThrow(() -> new NotFoundException("No resume has been uploaded yet."));
    }

    @Transactional(readOnly = true)
    public Optional<Resume> findActive() {
        return repository.findActive();
    }

    @Transactional(readOnly = true)
    public ResumeRepository.Original original() {
        Resume resume = active();
        return repository.findOriginal(resume.id())
                .orElseThrow(() -> new NotFoundException("The resume was pasted as text; there is no original file."));
    }

    /** Replaces the keyword list. Blank and repeated entries are dropped; at most {@link #MAX_KEYWORDS}. */
    @Transactional
    public Resume setKeywords(List<String> keywords) {
        Resume resume = active();
        Map<String, String> unique = new LinkedHashMap<>();
        for (String k : keywords == null ? List.<String>of() : keywords) {
            if (k != null && !k.isBlank()) {
                String trimmed = k.strip().replaceAll("\\s+", " ");
                if (trimmed.length() > MAX_KEYWORD_LENGTH) {
                    throw new IllegalArgumentException("A keyword is longer than " + MAX_KEYWORD_LENGTH + " characters.");
                }
                unique.putIfAbsent(Terms.key(trimmed), trimmed);
            }
        }
        if (unique.size() > MAX_KEYWORDS) {
            throw new IllegalArgumentException("At most " + MAX_KEYWORDS + " keywords are allowed.");
        }
        repository.updateKeywords(resume.id(), new ArrayList<>(unique.values()),
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        Resume updated = active();
        notifyListeners(updated);
        return updated;
    }

    @Transactional
    public void delete() {
        repository.findActive().ifPresent(r -> {
            repository.delete(r.id());
            notifyListeners(null);
        });
    }

    /** Years of experience the resume shows, for display. */
    public OptionalInt years(Resume resume) {
        return Experience.ofResume(resume.text(), Year.now(clock));
    }

    /** Listeners run after the commit, so a background job they start reads the stored resume. */
    private void notifyListeners(Resume resume) {
        Runnable notify = () -> listeners.forEach(l -> l.accept(resume));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    notify.run();
                }
            });
        } else {
            notify.run();
        }
    }

    private static String displayName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "Resume";
        }
        String base = fileName.replaceAll("^.*[\\\\/]", "");
        int dot = base.lastIndexOf('.');
        return dot > 0 ? base.substring(0, dot) : base;
    }
}
