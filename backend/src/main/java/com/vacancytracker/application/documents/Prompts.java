package com.vacancytracker.application.documents;

import com.vacancytracker.application.ai.AiClient.AiPrompt;
import com.vacancytracker.application.preset.Preset;
import com.vacancytracker.vacancy.Vacancy;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The prompts that adapt a base document to one vacancy (T-094, ADR-048). The system prompt is the unchangeable
 * guardrail (D7) followed by the preset's document prompt. The user message carries the data, each part in its own
 * tag: the goal with the weights, the base draft (and the base resume as the source of facts for a cover letter),
 * the vacancy, and the owner's optional instructions. The vacancy text comes from the internet: it is one data block,
 * and anything in it that looks like one of these tags is neutralised so it cannot close its block early.
 */
public final class Prompts {

    static final int MAX_TOKENS = 8000;
    static final String RESUME_TASK = "Adapt the base resume to the vacancy.";
    static final String COVER_LETTER_TASK = "Adapt the base cover letter to the vacancy.";

    private static final Pattern TAG = Pattern.compile(
            "(?i)<\\s*/?\\s*(goal|base_resume|base_cover_letter|vacancy|instructions|document|checklist)\\b[^>]{0,40}>");

    private Prompts() {
    }

    /** The system prompt: guardrail first, then the document prompt and the output format. */
    static String system(GenerationSnapshot s) {
        String format = TailoredDocument.RESUME.equals(s.kind())
                ? "Return only the adapted resume in Markdown."
                : "Return only the adapted cover letter as plain text.";
        return s.guardrail() + "\n\n" + s.prompt() + "\n\n" + format;
    }

    static AiPrompt adapt(GenerationSnapshot s, Vacancy vacancy, String instructions) {
        StringBuilder text = new StringBuilder();
        text.append(goal(s.mainGoal(), s.weights()));
        if (TailoredDocument.RESUME.equals(s.kind())) {
            block(text, "base_resume", s.baseDraft());
        } else {
            block(text, "base_cover_letter", s.baseDraft());
            if (s.baseResume() != null) {
                block(text, "base_resume", s.baseResume());
            }
        }
        text.append(vacancy(vacancy));
        if (instructions != null && !instructions.isBlank()) {
            block(text, "instructions", instructions.strip());
        }
        text.append("Task: ").append(TailoredDocument.RESUME.equals(s.kind()) ? RESUME_TASK : COVER_LETTER_TASK).append('\n');
        return new AiPrompt(system(s), text.toString(), MAX_TOKENS);
    }

    /** The goal block: the main goal prompt and each parameter's actual share of the weights. */
    public static String goal(String mainGoal, List<Preset.Weight> weights) {
        int total = weights.stream().mapToInt(Preset.Weight::weight).sum();
        StringBuilder text = new StringBuilder("<goal>\n").append(neutralise(mainGoal)).append("\n\nParameter weights (share of the total):\n");
        for (Preset.Weight w : weights) {
            long share = total == 0 ? 0 : Math.round(100.0 * w.weight() / total);
            text.append("- ").append(neutralise(w.name())).append(": ").append(share).append("%\n");
        }
        return text.append("</goal>\n\n").toString();
    }

    /** The vacancy as one data block. */
    public static String vacancy(Vacancy v) {
        StringBuilder text = new StringBuilder("<vacancy>\n");
        text.append("Title: ").append(neutralise(v.getTitle())).append('\n');
        text.append("Company: ").append(neutralise(v.getCompany())).append('\n');
        if (v.getLocation() != null) {
            text.append("Location: ").append(neutralise(v.getLocation())).append('\n');
        }
        text.append('\n').append(v.getDescription() == null ? "(no description)" : neutralise(v.getDescription()));
        return text.append("\n</vacancy>\n\n").toString();
    }

    public static void block(StringBuilder into, String tag, String content) {
        into.append('<').append(tag).append(">\n").append(neutralise(content)).append("\n</").append(tag).append(">\n\n");
    }

    /** Turns anything that looks like one of our tags into a bracketed word, so data cannot end its own block. */
    public static String neutralise(String text) {
        return text == null ? "" : TAG.matcher(text).replaceAll(m -> "[" + m.group(1).toLowerCase(Locale.ROOT) + "]");
    }
}
