package com.vacancytracker.application.preset;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The texts of the Default preset, read from resources/presets/default (T-093): the files hold sections D1 to D7 of
 * docs/specs/M7-ai-presets.md word for word, so the defaults live in resources and not in code. The guardrail (D7)
 * is not a preset field: it is put in front of every generation and cannot be edited.
 */
@Component
public class PresetDefaults {

    static final String DIR = "/presets/default/";

    private final String mainGoal;
    private final List<Preset.Weight> weights;
    private final String resumePrompt;
    private final String coverLetterPrompt;
    private final String baseResume;
    private final String baseCoverLetter;
    private final String guardrail;

    public PresetDefaults(ObjectMapper mapper) {
        this.mainGoal = text("main-goal-prompt.md");
        this.resumePrompt = text("resume-prompt.md");
        this.coverLetterPrompt = text("cover-letter-prompt.md");
        this.baseResume = text("base-resume.md");
        this.baseCoverLetter = text("base-cover-letter.md");
        this.guardrail = text("guardrail.md");
        try (InputStream in = open("weights.json")) {
            this.weights = List.copyOf(mapper.readValue(in, new TypeReference<List<Preset.Weight>>() {
            }));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the default weights", e);
        }
    }

    private static InputStream open(String name) {
        InputStream in = PresetDefaults.class.getResourceAsStream(DIR + name);
        if (in == null) {
            throw new IllegalStateException("Missing default preset resource " + DIR + name);
        }
        return in;
    }

    private static String text(String name) {
        try (InputStream in = open(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n").strip();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + name, e);
        }
    }

    public String mainGoal() {
        return mainGoal;
    }

    public List<Preset.Weight> weights() {
        return weights;
    }

    public String resumePrompt() {
        return resumePrompt;
    }

    public String coverLetterPrompt() {
        return coverLetterPrompt;
    }

    public String baseResume() {
        return baseResume;
    }

    public String baseCoverLetter() {
        return baseCoverLetter;
    }

    public String guardrail() {
        return guardrail;
    }
}
