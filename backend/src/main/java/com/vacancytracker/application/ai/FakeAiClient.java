package com.vacancytracker.application.ai;

import com.vacancytracker.application.resume.Terms;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * A fixed stand-in for Claude, for tests and the end-to-end test (app.ai.provider=fake). It sends nothing anywhere
 * and answers from the prompt itself, deterministically, so a test can predict every answer and check what reached
 * it ({@link #lastPrompt()}). Never use it for real applications.
 *
 * <p>Adapting a resume adds one bullet naming up to two skills the vacancy asks for and the base resume lacks: a
 * deliberate overreach, so the fact check (T-098) has something to catch and the match visibly rises.
 */
@Component
public class FakeAiClient implements AiClient {

    private static final Pattern FIELD = Pattern.compile("(?m)^(Title|Company|Task): (.+)$");

    private volatile AiPrompt last;

    @Override
    public String provider() {
        return "fake";
    }

    @Override
    public boolean configured() {
        return true;
    }

    /** The prompt of the last call, for tests. */
    public AiPrompt lastPrompt() {
        return last;
    }

    @Override
    public AiReply complete(AiPrompt prompt) {
        last = prompt;
        String task = lastTask(prompt.user());
        String text;
        if (task.startsWith("Adapt the base resume")) {
            text = adaptResume(block(prompt.user(), "base_resume"), block(prompt.user(), "vacancy"));
        } else if (task.startsWith("Adapt the base cover letter")) {
            text = adaptLetter(block(prompt.user(), "base_cover_letter"), field(prompt.user(), "Title"), field(prompt.user(), "Company"));
        } else {
            text = "(Fake answer for tests.)\n";
        }
        return new AiReply(text, provider(), "fake-model");
    }

    static String adaptResume(String base, String vacancy) {
        List<String> missing = new ArrayList<>();
        for (String term : Terms.skillLikeTerms(vacancy)) {
            if (!Terms.mentions(base, term) && missing.size() < 2) {
                missing.add(term);
            }
        }
        if (missing.isEmpty()) {
            return base;
        }
        String bullet = "- Built production services with " + String.join(" and ", missing) + ".";
        List<String> lines = new ArrayList<>(List.of(base.split("\n", -1)));
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("- ")) {
                lines.add(i, bullet);
                return String.join("\n", lines);
            }
        }
        return base + "\n" + bullet;
    }

    static String adaptLetter(String base, String title, String company) {
        String[] paragraphs = base.split("\n\n", 2);
        String opening = "I am applying for the " + title + " role at " + company + ".";
        return paragraphs.length == 2 ? paragraphs[0] + "\n\n" + opening + "\n\n" + paragraphs[1] : opening + "\n\n" + base;
    }

    /** The content of the first <tag>…</tag> block, or an empty string. */
    static String block(String text, String tag) {
        int start = text.indexOf("<" + tag + ">\n");
        int end = text.indexOf("\n</" + tag + ">", start + 1);
        return start < 0 || end < 0 ? "" : text.substring(start + tag.length() + 3, end);
    }

    /** The task is the last line of the prompt; a "Task:" line inside the vacancy text is data, not the task. */
    private static String lastTask(String text) {
        String task = "?";
        Matcher m = FIELD.matcher(text);
        while (m.find()) {
            if (m.group(1).equals("Task")) {
                task = m.group(2).trim();
            }
        }
        return task;
    }

    private static String field(String text, String name) {
        Matcher m = FIELD.matcher(text);
        while (m.find()) {
            if (m.group(1).equals(name)) {
                return m.group(2).trim();
            }
        }
        return "?";
    }
}
