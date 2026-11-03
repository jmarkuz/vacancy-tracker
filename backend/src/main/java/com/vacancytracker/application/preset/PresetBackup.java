package com.vacancytracker.application.preset;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.vacancytracker.application.resume.Resume;
import com.vacancytracker.application.resume.ResumeRepository;
import com.vacancytracker.application.resume.ResumeService;
import com.vacancytracker.common.BackupSection;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The "presets" section of the JSON backup (T-093): every preset with its base resume (text, and the uploaded file in
 * Base64 when there is one). Restoring updates a preset with the same id or name and adds the others; the preset that
 * was the default becomes the default again, so its base resume is the active resume.
 */
@Component
public class PresetBackup implements BackupSection {

    private final PresetRepository repository;
    private final PresetService presets;
    private final ResumeService resumes;
    private final Clock clock;

    public PresetBackup(PresetRepository repository, PresetService presets, ResumeService resumes, Clock clock) {
        this.repository = repository;
        this.presets = presets;
        this.resumes = resumes;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "presets";
    }

    @Override
    @Transactional(readOnly = true)
    public void write(JsonGenerator gen) throws IOException {
        gen.writeStartArray();
        for (Preset p : repository.findAll()) {
            gen.writeStartObject();
            gen.writeStringField("id", p.id().toString());
            gen.writeStringField("name", p.name());
            gen.writeBooleanField("isDefault", p.isDefault());
            gen.writeStringField("mainGoal", p.mainGoal());
            gen.writeArrayFieldStart("weights");
            for (Preset.Weight w : p.weights()) {
                gen.writeStartObject();
                gen.writeStringField("name", w.name());
                gen.writeNumberField("weight", w.weight());
                gen.writeEndObject();
            }
            gen.writeEndArray();
            gen.writeStringField("resumePrompt", p.resumePrompt());
            gen.writeStringField("coverLetterPrompt", p.coverLetterPrompt());
            gen.writeStringField("baseCoverLetter", p.baseCoverLetter());
            gen.writeStringField("baseCoverLetterFileName", p.baseCoverLetterFileName());
            Optional<Resume> base = resumes.find(p.baseResumeId());
            if (base.isPresent()) {
                Resume r = base.get();
                gen.writeObjectFieldStart("baseResume");
                gen.writeStringField("name", r.name());
                gen.writeStringField("fileName", r.fileName());
                gen.writeStringField("contentType", r.contentType());
                gen.writeStringField("text", r.text());
                Optional<ResumeRepository.Original> original = resumes.originalOf(r.id());
                if (original.isPresent()) {
                    gen.writeStringField("original", Base64.getEncoder().encodeToString(original.get().bytes()));
                }
                gen.writeEndObject();
            }
            gen.writeEndObject();
        }
        gen.writeEndArray();
    }

    @Override
    @Transactional
    public int restore(JsonNode value) {
        if (!value.isArray()) {
            throw new IllegalArgumentException("Backup field 'presets' must be an array");
        }
        int restored = 0;
        UUID makeDefault = null;
        for (JsonNode item : value) {
            Preset p = restoreOne(item);
            restored++;
            if (item.path("isDefault").asBoolean(false)) {
                makeDefault = p.id();
            }
        }
        if (makeDefault != null) {
            presets.makeDefault(makeDefault);
        }
        return restored;
    }

    private Preset restoreOne(JsonNode item) {
        String name = text(item, "name");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A preset in the backup has no name");
        }
        List<Preset.Weight> weights = new ArrayList<>();
        for (JsonNode w : item.path("weights")) {
            weights.add(new Preset.Weight(text(w, "name"), w.path("weight").asInt(0)));
        }
        UUID id = parseId(text(item, "id"));
        Optional<Preset> existing = (id == null ? Optional.<Preset>empty() : repository.find(id)).or(() -> repository.findByName(name.strip()));
        UUID baseResumeId = restoreResume(item.path("baseResume")).orElse(existing.map(Preset::baseResumeId).orElse(null));
        Instant now = clock.instant();
        Preset p = new Preset(existing.map(Preset::id).orElse(id == null ? UUID.randomUUID() : id), name.strip(),
                existing.map(Preset::isDefault).orElse(false),
                required(item, "mainGoal"), PresetService.validWeights(weights), required(item, "resumePrompt"),
                required(item, "coverLetterPrompt"), baseResumeId, required(item, "baseCoverLetter"),
                text(item, "baseCoverLetterFileName"), existing.map(Preset::createdAt).orElse(now), now);
        if (existing.isPresent()) {
            repository.update(p);
        } else {
            repository.insert(p);
        }
        return p;
    }

    private Optional<UUID> restoreResume(JsonNode node) {
        if (node.isMissingNode() || node.isNull() || text(node, "text") == null) {
            return Optional.empty();
        }
        String original = text(node, "original");
        String fileName = text(node, "fileName");
        Resume r = original != null && fileName != null
                ? resumes.uploadBase(fileName, text(node, "contentType"), Base64.getDecoder().decode(original), false)
                : resumes.pasteBase(text(node, "name"), text(node, "text"), false);
        return Optional.of(r.id());
    }

    private static String required(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A preset in the backup has no " + field);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static UUID parseId(String id) {
        try {
            return id == null ? null : UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
