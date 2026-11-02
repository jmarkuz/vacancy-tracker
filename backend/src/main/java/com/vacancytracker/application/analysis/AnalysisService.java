package com.vacancytracker.application.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vacancytracker.application.ai.AiClient;
import com.vacancytracker.application.ai.AiService;
import com.vacancytracker.application.documents.DocumentService;
import com.vacancytracker.application.documents.TailoredDocument;
import com.vacancytracker.application.fit.ResumeFit;
import com.vacancytracker.application.fit.ResumeFitService;
import com.vacancytracker.application.preset.Placeholders;
import com.vacancytracker.application.preset.Preset;
import com.vacancytracker.application.preset.PresetService;
import com.vacancytracker.vacancy.Vacancy;
import com.vacancytracker.vacancy.VacancyService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Employer match and Goal match of a vacancy (T-095, ADR-049). With AI on, one call per vacancy text builds the
 * requirement checklist (cached by the hash of the text); for each document a call proposes a status and a quote per
 * requirement, the server checks every quote and computes the score; one call per goal rates the vacancy against the
 * preset's parameters and code averages them by weight. Everything is cached by the hash of what it was computed from,
 * so the same text always gets the same score, and a changed vacancy text makes the old results stale. Without AI the
 * drawer shows the quick fit (T-081) only.
 */
@Service
public class AnalysisService {

    public static final String NONE = "none";
    public static final String CURRENT = "current";
    public static final String STALE = "stale";
    public static final String NOT_READY = "not_ready";
    static final int MAX_REQUIREMENTS = 20;

    /** A document's Employer match; source is base (the preset's base draft) or version (a saved version). */
    public record DocumentScore(String source, UUID documentId, Integer version, EmployerMatch match) {
    }

    /**
     * Everything the drawer shows. checklistState and goalState are none (not computed yet), current, stale (the
     * vacancy text changed since) or not_ready (goal: the preset's main goal still has placeholders).
     */
    public record Analysis(boolean aiEnabled, ResumeFit quickFit, String checklistState, Checklist checklist, String goalState,
                           GoalMatch goal, DocumentScore baseResume, DocumentScore baseCoverLetter, DocumentScore resume,
                           DocumentScore coverLetter) {
    }

    private static final TypeReference<List<EmployerMatch.Item>> ITEMS = new TypeReference<>() {
    };

    private final AiService ai;
    private final VacancyService vacancies;
    private final PresetService presets;
    private final DocumentService documents;
    private final ResumeFitService fits;
    private final AnalysisRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AnalysisService(AiService ai, VacancyService vacancies, PresetService presets, DocumentService documents,
                           ResumeFitService fits, AnalysisRepository repository, ObjectMapper mapper, Clock clock) {
        this.ai = ai;
        this.vacancies = vacancies;
        this.presets = presets;
        this.documents = documents;
        this.fits = fits;
        this.repository = repository;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** What is known now, without calling AI. */
    public Analysis read(UUID vacancyId, UUID presetId) {
        return analyse(vacancyId, presetId, false);
    }

    /** Computes what is missing or stale with AI when it is on, then answers as {@link #read}. */
    public Analysis run(UUID vacancyId, UUID presetId) {
        return analyse(vacancyId, presetId, ai.status().enabled());
    }

    /** The Employer match of any text (a draft, an edited version) against the vacancy's current checklist. Needs AI. */
    public EmployerMatch matchText(UUID vacancyId, String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("The text is empty.");
        }
        Vacancy v = vacancies.get(vacancyId);
        CurrentChecklist cl = checklist(v, true).orElseThrow();
        return match(vacancyId, cl, text, true);
    }

    private Analysis analyse(UUID vacancyId, UUID presetId, boolean compute) {
        Vacancy v = vacancies.get(vacancyId);
        boolean enabled = ai.status().enabled();
        ResumeFit quickFit = fits.fitFor(vacancyId).orElse(null);
        Preset preset = presets.getOrDefault(presetId);
        String textHash = textHash(v);

        Optional<AnalysisRepository.StoredChecklist> stored = repository.checklist(vacancyId);
        Optional<CurrentChecklist> checklist = checklist(v, compute);
        String checklistState = checklist.isPresent() ? CURRENT : stored.isPresent() ? STALE : NONE;

        String goalState;
        GoalMatch goal = null;
        if (!Placeholders.of(preset.mainGoal()).isEmpty()) {
            goalState = NOT_READY;
        } else {
            String goalHash = hash(preset.mainGoal() + "\n" + json(preset.weights()));
            Optional<AnalysisRepository.StoredGoal> storedGoal = repository.goal(vacancyId, goalHash);
            if (compute && storedGoal.map(g -> !g.textHash().equals(textHash)).orElse(true)) {
                goal = rateGoal(v, preset);
                repository.saveGoal(vacancyId, goalHash, textHash, json(goal), clock.instant());
                goalState = CURRENT;
            } else if (storedGoal.isPresent()) {
                goal = read(storedGoal.get().json(), GoalMatch.class);
                goalState = storedGoal.get().textHash().equals(textHash) ? CURRENT : STALE;
            } else {
                goalState = NONE;
            }
        }

        DocumentScore baseResume = null;
        DocumentScore baseLetter = null;
        DocumentScore resume = null;
        DocumentScore letter = null;
        if (checklist.isPresent()) {
            CurrentChecklist cl = checklist.get();
            PresetService.BaseResume base = presets.baseResume(preset);
            if (!base.template() && Placeholders.of(base.text()).isEmpty()) {
                baseResume = score("base", null, null, match(vacancyId, cl, base.text(), compute));
            }
            if (Placeholders.of(preset.baseCoverLetter()).isEmpty()) {
                baseLetter = score("base", null, null, match(vacancyId, cl, preset.baseCoverLetter(), compute));
            }
            Map<String, TailoredDocument> latest = new HashMap<>();
            for (TailoredDocument d : documents.list(vacancyId)) {
                latest.merge(d.kind(), d, (a, b) -> a.version() >= b.version() ? a : b);
            }
            TailoredDocument r = latest.get(TailoredDocument.RESUME);
            if (r != null) {
                resume = score("version", r.id(), r.version(), match(vacancyId, cl, r.content(), compute));
            }
            TailoredDocument l = latest.get(TailoredDocument.COVER_LETTER);
            if (l != null) {
                letter = score("version", l.id(), l.version(), match(vacancyId, cl, l.content(), compute));
            }
        }
        return new Analysis(enabled, quickFit, checklistState, checklist.map(CurrentChecklist::checklist).orElse(null), goalState, goal,
                baseResume, baseLetter, resume, letter);
    }

    private static DocumentScore score(String source, UUID id, Integer version, EmployerMatch match) {
        return match == null ? null : new DocumentScore(source, id, version, match);
    }

    /** The checklist and its hash, as long as it was built from the vacancy's current text. */
    private record CurrentChecklist(Checklist checklist, String hash) {
    }

    private Optional<CurrentChecklist> checklist(Vacancy v, boolean compute) {
        String textHash = textHash(v);
        Optional<AnalysisRepository.StoredChecklist> stored = repository.checklist(v.getId());
        if (stored.isPresent() && stored.get().textHash().equals(textHash)) {
            return Optional.of(new CurrentChecklist(read(stored.get().json(), Checklist.class), stored.get().checklistHash()));
        }
        if (!compute) {
            return Optional.empty();
        }
        AiClient.AiReply reply = ai.complete(AnalysisPrompts.checklist(presets.defaults().guardrail(), v));
        Checklist checklist = parseChecklist(JsonReply.parse(mapper, reply.text()));
        String json = json(checklist);
        String hash = hash(json);
        repository.saveChecklist(v.getId(), textHash, hash, json, reply.provider(), reply.model(), clock.instant());
        return Optional.of(new CurrentChecklist(checklist, hash));
    }

    static Checklist parseChecklist(JsonNode root) {
        List<Checklist.Requirement> requirements = new ArrayList<>();
        for (JsonNode r : root.path("requirements")) {
            String text = r.path("text").asText("").strip();
            if (text.isEmpty() || requirements.size() >= MAX_REQUIREMENTS) {
                continue;
            }
            List<String> terms = new ArrayList<>();
            for (JsonNode t : r.path("terms")) {
                if (!t.asText("").isBlank() && terms.size() < 10) {
                    terms.add(t.asText().strip());
                }
            }
            requirements.add(new Checklist.Requirement("r" + (requirements.size() + 1), cut(text, 300), priority(r.path("priority").asText("")),
                    List.copyOf(terms)));
        }
        if (requirements.isEmpty()) {
            throw new com.vacancytracker.application.ai.AiFailedException("Claude found no requirements in this vacancy.");
        }
        return new Checklist(cut(root.path("coreProblem").asText("").strip(), 500), List.copyOf(requirements));
    }

    private static String priority(String raw) {
        String p = raw.toLowerCase(Locale.ROOT);
        return p.startsWith("must") ? Checklist.MUST : p.startsWith("nice") ? Checklist.NICE : Checklist.IMPORTANT;
    }

    /** Statuses for one text: from the cache, or proposed by AI and checked here. Null when not cached and compute is off. */
    private EmployerMatch match(UUID vacancyId, CurrentChecklist cl, String text, boolean compute) {
        String textHash = hash(text);
        List<Checklist.Requirement> requirements = cl.checklist().requirements();
        Optional<String> cached = repository.match(vacancyId, cl.hash(), textHash);
        if (cached.isPresent()) {
            List<EmployerMatch.Item> items = read(cached.get(), ITEMS);
            return new EmployerMatch(EmployerMatch.score(requirements, items), items);
        }
        if (!compute) {
            return null;
        }
        AiClient.AiReply reply = ai.complete(AnalysisPrompts.status(presets.defaults().guardrail(), json(cl.checklist()), text));
        Map<String, JsonNode> proposed = new LinkedHashMap<>();
        for (JsonNode item : JsonReply.parse(mapper, reply.text()).path("items")) {
            proposed.putIfAbsent(item.path("id").asText(""), item);
        }
        List<EmployerMatch.Item> items = new ArrayList<>();
        for (Checklist.Requirement r : requirements) {
            items.add(check(r, proposed.get(r.id()), text));
        }
        repository.saveMatch(vacancyId, cl.hash(), textHash, json(items), clock.instant());
        return new EmployerMatch(EmployerMatch.score(requirements, items), items);
    }

    /** The model's status after the server's checks: the quote must be in the text; Skills alone gives at most partial. */
    static EmployerMatch.Item check(Checklist.Requirement r, JsonNode proposed, String text) {
        String status = proposed == null ? EmployerMatch.MISSING : proposed.path("status").asText("").toLowerCase(Locale.ROOT).strip();
        String quote = proposed == null ? "" : proposed.path("quote").asText("").strip();
        if (!EmployerMatch.COVERED.equals(status) && !EmployerMatch.PARTIAL.equals(status)) {
            return new EmployerMatch.Item(r.id(), r.text(), r.priority(), EmployerMatch.MISSING, null, null);
        }
        return switch (QuoteCheck.place(text, quote)) {
            case NOT_FOUND -> new EmployerMatch.Item(r.id(), r.text(), r.priority(), EmployerMatch.MISSING, null,
                    "The proof Claude quoted is not in the document.");
            case SKILLS_ONLY -> new EmployerMatch.Item(r.id(), r.text(), r.priority(), EmployerMatch.PARTIAL, cut(quote, 300),
                    EmployerMatch.COVERED.equals(status) ? "Named only in Skills: covered needs proof in experience or projects." : null);
            case EVIDENCE -> new EmployerMatch.Item(r.id(), r.text(), r.priority(), status, cut(quote, 300), null);
        };
    }

    private GoalMatch rateGoal(Vacancy v, Preset preset) {
        AiClient.AiReply reply = ai.complete(AnalysisPrompts.goal(presets.defaults().guardrail(), preset.mainGoal(), preset.weights(), v));
        JsonNode root = JsonReply.parse(mapper, reply.text());
        Map<String, JsonNode> rated = new HashMap<>();
        for (JsonNode p : root.path("parameters")) {
            rated.putIfAbsent(p.path("name").asText("").strip().toLowerCase(Locale.ROOT), p);
        }
        List<Double> shares = preset.shares();
        List<GoalMatch.Parameter> parameters = new ArrayList<>();
        for (int i = 0; i < preset.weights().size(); i++) {
            Preset.Weight w = preset.weights().get(i);
            JsonNode p = rated.get(w.name().toLowerCase(Locale.ROOT));
            int score = p == null ? 50 : Math.max(0, Math.min(100, p.path("score").asInt(50)));
            String why = p == null ? "Not rated by Claude." : cut(p.path("explanation").asText("").strip(), 400);
            parameters.add(new GoalMatch.Parameter(w.name(), w.weight(), shares.get(i), score, why));
        }
        JsonNode dealBreaker = root.path("dealBreaker");
        boolean found = dealBreaker.path("found").asBoolean(false);
        return new GoalMatch(GoalMatch.score(parameters), List.copyOf(parameters), found,
                found ? cut(dealBreaker.path("explanation").asText("").strip(), 400) : null);
    }

    /** The hash of every vacancy field the prompts send (see Prompts.vacancy): a change to any of them makes results stale. */
    static String textHash(Vacancy v) {
        return hash(String.join("\n", nullToEmpty(v.getTitle()), nullToEmpty(v.getCompany()), nullToEmpty(v.getLocation()),
                nullToEmpty(v.getDescription())));
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is part of every Java runtime", e);
        }
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize the analysis", e);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A stored analysis is not valid JSON", e);
        }
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A stored analysis is not valid JSON", e);
        }
    }
}
